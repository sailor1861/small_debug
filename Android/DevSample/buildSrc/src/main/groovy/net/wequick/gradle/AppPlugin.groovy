/*
 * Copyright 2015-present wequick.net
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package net.wequick.gradle

import com.android.build.api.transform.Format
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.internal.pipeline.IntermediateFolderUtils
import com.android.build.gradle.internal.pipeline.TransformTask
import com.android.build.gradle.internal.transforms.ProGuardTransform
import com.android.build.gradle.tasks.MergeManifests
import com.android.build.gradle.tasks.MergeResources
import com.android.build.gradle.tasks.MergeSourceSetFolders
import com.android.build.gradle.tasks.ProcessAndroidResources
import com.android.sdklib.BuildToolInfo
import groovy.io.FileType
import net.wequick.gradle.aapt.Aapt
import net.wequick.gradle.aapt.SymbolParser
import net.wequick.gradle.transform.StripAarTransform
import net.wequick.gradle.util.JNIUtils
import net.wequick.gradle.util.ZipUtils
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.file.FileTree
import org.gradle.api.internal.artifacts.dependencies.DefaultProjectDependency
import org.gradle.api.tasks.compile.JavaCompile

class AppPlugin extends BundlePlugin {

    private static final int UNSET_TYPEID = 99
    private static final int UNSET_ENTRYID = -1
    protected static def sPackageIds = [:] as LinkedHashMap<String, Integer>

    /** 插件依赖的lib插件工程 */
    protected Set<Project> mDependentLibProjects
    /** 插件依赖的普遍工程 */
    protected Set<Map> mUserLibAars
    protected Set<File> mLibraryJars
    protected File mMinifyJar

    void apply(Project project) {
        super.apply(project)
    }

    @Override
    protected Class<? extends BaseExtension> getExtensionClass() {
        return AppExtension.class
    }

    @Override
    protected PluginType getPluginType() {
        return PluginType.App
    }

    @Override
    protected void createExtension() {
        super.createExtension()
    }

    @Override
    protected AppExtension getSmall() {
        return super.getSmall()
    }

    @Override
    protected void configureProject() {
        super.configureProject()

        project.afterEvaluate {
            // 遍历dependencies
            // Get all dependencies with gradle script `compile project(':lib.*')'
            DependencySet compilesDependencies = project.configurations.compile.dependencies
            Set<DefaultProjectDependency> allLibs = compilesDependencies.withType(DefaultProjectDependency.class)
            Set<DefaultProjectDependency> smallLibs = []
            mUserLibAars = []
            mDependentLibProjects = []
            allLibs.each {
                Log.success "[${project.name}] check compilesDependencies($it.dependencyProject.name)"

                // 区分lib库(公共库插件)和其他普通依赖库
                if (it.dependencyProject.name.startsWith('lib.')) {
                    smallLibs.add(it)
                    mDependentLibProjects.add(it.dependencyProject)
                } else {
                    mUserLibAars.add(group: it.group, name: it.name, version: it.version)
                    Log.success "[${project.name}] add mUserLibAars($it.dependencyProject.name)"
                }
            }
            // 编译lib公共插件时，移除对lib的依赖
            if (isBuildingLibs()) {
                // While building libs, `lib.*' modules are changing to be an application
                // module and cannot be depended by any other modules. To avoid warnings,
                // remove the `compile project(':lib.*')' dependencies temporary.
                compilesDependencies.removeAll(smallLibs)
            }
            Log.success "[${project.name}] mUserLibAars($mUserLibAars)"
        }

        if (!isBuildingRelease()) return

        project.afterEvaluate {
            // Add custom transformation to split shared libraries
            android.registerTransform(new StripAarTransform())

            initPackageId()
            resolveReleaseDependencies()
        }

    }

    protected static def getJarName(Project project) {
        def group = project.group
        if (group == project.rootProject.name) group = project.name
        return "$group-${project.version}.jar"
    }

    protected static Set<File> getJarDependencies(Project project) {
        return project.fileTree(dir: 'libs', include: '*.jar').asList()
    }

    protected Set<File> getLibraryJars() {
        if (mLibraryJars != null) return mLibraryJars

        mLibraryJars = new LinkedHashSet<File>()

        // Collect the jars in `build-small/intermediates/small-pre-jar/base'
        def baseJars = project.fileTree(dir: rootSmall.preBaseJarDir, include: ['*.jar'])
        mLibraryJars.addAll(baseJars.files)

        // Collect the jars of `compile project(lib.*)' with absolute file path, fix issue #65
        Set<String> libJarNames = []
        Set<File> libDependentJars = []
        mDependentLibProjects.each {
            libJarNames += getJarName(it)
            libDependentJars += getJarDependencies(it)
        }

        if (libJarNames.size() > 0) {
            def libJars = project.files(libJarNames.collect{
                new File(rootSmall.preLibsJarDir, it).path
            })
            mLibraryJars.addAll(libJars.files)
        }

        mLibraryJars.addAll(libDependentJars)

        return mLibraryJars
    }

    protected void resolveReleaseDependencies() {
        // Pre-split all the jar dependencies (deep level)
        def compile = project.configurations.compile
        compile.exclude group: 'com.android.support', module: 'support-annotations'
        rootSmall.preLinkJarDir.listFiles().each { file ->
            if (!file.name.endsWith('D.txt')) return
            if (file.name.startsWith(project.name)) return

            file.eachLine { line ->
                def module = line.split(':')
                compile.exclude group: module[0], module: module[1]
            }
        }
    }

    @Override
    protected void configureDebugVariant(BaseVariant variant) {
        super.configureDebugVariant(variant)

        if (pluginType != PluginType.App) return

        // If an app.A dependent by lib.B and both of them declare application@name in their
        // manifests, the `processManifest` task will raise a conflict error. To avoid this,
        // modify the lib.B manifest to remove the attributes before app.A `processManifest`
        // and restore it after the task finished.
        Task processDebugManifest = project.tasks["process${variant.name.capitalize()}Manifest"]
        processDebugManifest.doFirst { MergeManifests it ->
            def libs = it.libraries
            def libManifests = []
            libs.each {
                if (it.name.contains(':lib.')) {
                    libManifests.add(it.manifest)
                }
            }
            def filteredManifests = []
            libManifests.each { File manifest ->
                def sb = new StringBuilder()
                def enteredApplicationNode = false
                def needsFilter = true
                def filtered = false
                manifest.eachLine { line ->
                    if (!needsFilter && !filtered) return

                    while (true) { // fake loop for less `if ... else' statement
                        if (!needsFilter) break

                        def i = line.indexOf('<application')
                        if (i < 0) {
                            if (!enteredApplicationNode) break

                            if (line.indexOf('>') > 0) needsFilter = false

                            // filter `android:name'
                            if (line.indexOf('android:name') > 0) {
                                filtered = true
                                if (needsFilter) return

                                line = '>'
                            }
                            break
                        }

                        def j = line.indexOf('<!--')
                        if (j > 0 && j < i) break // ignores the comment line

                        if (line.indexOf('>') > 0) { // <application /> or <application .. > in one line
                            needsFilter = false
                            def k = line.indexOf('android:name="')
                            if (k > 0) {
                                filtered = true
                                def k_ = line.indexOf('"', k + 15) // bypass 'android:name='
                                line = line.substring(0, k) + line.substring(k_ + 1)
                            }
                            break
                        }

                        enteredApplicationNode = true // mark this for next line
                        break
                    }

                    sb.append(line).append(System.lineSeparator())
                }

                if (filtered) {
                    def backupManifest = new File(manifest.parentFile, "${manifest.name}~")
                    manifest.renameTo(backupManifest)
                    manifest.write(sb.toString(), 'utf-8')
                    filteredManifests.add(overwrite: manifest, backup: backupManifest)
                }
            }
            ext.filteredManifests = filteredManifests
        }
        processDebugManifest.doLast {
            ext.filteredManifests.each {
                it.backup.renameTo(it.overwrite)
            }
        }
    }

    @Override
    protected void configureReleaseVariant(BaseVariant variant) {
        super.configureReleaseVariant(variant)

        // Fill extensions
        def variantName = variant.name.capitalize()
        File mergerDir = variant.mergeResources.incrementalFolder

        // 给small扩展赋值
        small.with {
            javac = variant.javaCompile
            processManifest = project.tasks["process${variantName}Manifest"]

            packageName = variant.applicationId
            packagePath = packageName.replaceAll('\\.', '/')
            classesDir = javac.destinationDir
            bkClassesDir = new File(classesDir.parentFile, "${classesDir.name}~")

            aapt = (ProcessAndroidResources) project.tasks["process${variantName}Resources"]
            apFile = aapt.packageOutputFile

            File symbolDir = aapt.textSymbolOutputDir
            File sourceDir = aapt.sourceOutputDir

            // Aapt后生成的symbolFile，通过固定该文件，可以固定资源ID
            symbolFile = new File(symbolDir, 'R.txt')
            // Application工程普通compile的R.java
            rJavaFile = new File(sourceDir, "${packagePath}/R.java")

            // 待分离的R.java: 只有App自身的R.java: 未合成公共R前的App自身的R.java; 用于打包到插件自身包内;
            // Q:  插件也可以引入单独的第三方组件, 这些aar的R，如何处理呢？
            splitRJavaFile = new File(sourceDir.parentFile, "small/${packagePath}/R.java")
            Log.success "[${project.name}] rJavaFile: $rJavaFile ; split R.class : $splitRJavaFile ; exists-->" + splitRJavaFile.exists()

            mergerXml = new File(mergerDir, 'merger.xml')
        }

        hookVariantTask(variant)
    }

    @Override
    protected void configureProguard(BaseVariant variant, TransformTask proguard, ProGuardTransform pt) {
        super.configureProguard(variant, proguard, pt)

        // Keep R.*
        // FIXME: the `configuration' field is protected, may be depreciated
        pt.configuration.keepAttributes = ['InnerClasses']
        pt.keep("class ${variant.applicationId}.R")
        pt.keep("class ${variant.applicationId}.R\$* { <fields>; }")

        // Add reference libraries
        proguard.doFirst {
            getLibraryJars().each {
                // FIXME: the `libraryJar' method is protected, may be depreciated
                pt.libraryJar(it)
            }
        }
        // Split R.class
        proguard.doLast {
            Log.success("[$project.name] Strip aar classes...")

            if (small.splitRJavaFile == null) return

            def minifyJar = IntermediateFolderUtils.getContentLocation(
                    proguard.streamOutputFolder, 'main', pt.outputTypes, pt.scopes, Format.JAR)
            if (!minifyJar.exists()) return

            mMinifyJar = minifyJar // record for `LibraryPlugin'

            // Unpack the minify jar to split the R.class
            File unzipDir = new File(minifyJar.parentFile, 'main')
            project.copy {
                from project.zipTree(minifyJar)
                into unzipDir
            }

            def javac = small.javac
            File pkgDir = new File(unzipDir, small.packagePath)

            // Delete the original generated R$xx.class
            pkgDir.listFiles().each { f ->
                if (f.name.startsWith('R$')) {
                    f.delete()
                }
            }

            // Re-compile the split R.java to R.class
            project.ant.javac(srcdir: small.splitRJavaFile.parentFile,
                    source: javac.sourceCompatibility,
                    target: javac.targetCompatibility,
                    destdir: unzipDir)

            // Repack the minify jar
            project.ant.zip(baseDir: unzipDir, destFile: minifyJar)

            Log.success "[${project.name}] split R.class..."
        }
    }

    /** Collect the vendor aars (has resources) compiling in current bundle */
    protected void collectVendorAars(Set<Map> outFirstLevelAars,
                                     Set<Map> outTransitiveAars) {
        project.configurations.compile.resolvedConfiguration.firstLevelModuleDependencies.each {
            collectVendorAars(it, outFirstLevelAars, outTransitiveAars)
        }
    }

    protected boolean collectVendorAars(ResolvedDependency node,
                                        Set<Map> outFirstLevelAars,
                                        Set<Map> outTransitiveAars) {
        def group = node.moduleGroup,
            name = node.moduleName,
            version = node.moduleVersion

        if (group == '' && version == '') {
            // Ignores the dependency of local aar
            return false
        }
        if (small.splitAars.find { aar -> group == aar.group && name == aar.name } != null) {
            // Ignores the dependency which has declared in host or lib.*
            return false
        }
        if (small.retainedAars.find { aar -> group == aar.group && name == aar.name } != null) {
            // Ignores the dependency of normal modules
            return false
        }

        def path = "$group/$name/$version"
        def aar = [path: path, name: node.name, version: version]
        def resDir = new File(small.aarDir, "$path/res")
        // If the dependency has resources, collect it
        if (resDir.exists() && resDir.list().size() > 0) {
            if (outFirstLevelAars != null && !outFirstLevelAars.contains(aar)) {
                outFirstLevelAars.add(aar)
            }
            if (!outTransitiveAars.contains(aar)) {
                outTransitiveAars.add(aar)
            }
            node.children.each { next ->
                collectVendorAars(next, null, outTransitiveAars)
            }
            return true
        }

        // Otherwise, check it's children for recursively collecting
        boolean flag = false
        node.children.each { next ->
            flag |= collectVendorAars(next, null, outTransitiveAars)
        }
        if (!flag) return false

        if (outFirstLevelAars != null && !outFirstLevelAars.contains(aar)) {
            outFirstLevelAars.add(aar)
        }
        return true
    }

    // 特别关键、特别难以理解的一个类：保证public.txt内的ID，能够固定下来的机制
    /**
     * Prepare retained resource types and resource id maps for package slicing
     */
    protected void prepareSplit() {
        // 判断本工程，是否有R文件，没有的话，直接退出；
        def idsFile = small.symbolFile
        if (!idsFile.exists()) return

        // 收集所有Vendor.aar
        // Check if has any vendor aars
        def firstLevelVendorAars = [] as Set<Map>
        def transitiveVendorAars = [] as Set<Map>
        collectVendorAars(firstLevelVendorAars, transitiveVendorAars)
        if (firstLevelVendorAars.size() > 0) {
            if (rootSmall.strictSplitResources) {
                def err = new StringBuilder('In strict mode, we do not allow vendor aars, ')
                err.append('please declare them in host build.gradle:\n')
                firstLevelVendorAars.each {
                    err.append("    - compile('${it.name}')\n")
                }
                err.append('or turn off the strict mode in root build.gradle:\n')
                err.append('    small {\n')
                err.append('        strictSplitResources = false\n')
                err.append('    }')
                throw new UnsupportedOperationException(err.toString())
            } else {
                def aars = firstLevelVendorAars.collect{ it.name }.join('; ')
                Log.warn("Using vendor aar(s): $aars")
            }
        }

        // 添加所有普通compile的aar ： 目的仅仅是为了vendor types and styleables，使用！
        // Add user retained aars for generating their R.java, fix #194
        if (small.retainedAars != null) {
            transitiveVendorAars.addAll(small.retainedAars.collect {
                [path: "$it.group/$it.name/$it.version", version: it.version]
            })
        }
        Log.success "add transitiveVendorAars($transitiveVendorAars), publicSymbolFile($small.publicSymbolFile)"

        // 从rootSmall.preIdsDir找到非本工程的所有lib库(公共插件)的R.txt文件; 这些资源均是需要过滤掉的
        // 目的：生成staticIdMaps();
        // Q: 依赖的lib库的R.txt，如何获取？
        // A: buildLib时，生成到该目录的
        // Prepare id maps (bundle resource id -> library resource id)
        def libEntries = [:]
        rootSmall.preIdsDir.listFiles().each {  // todo: host共享库的资源也需要加入到这里！ host编译后，也是讲pulbic.txt copy到 rootSmall.preIdsDir
            if (it.name.endsWith('R.txt') && !it.name.startsWith(project.name)) {
                libEntries += SymbolParser.getResourceEntries(it)
            }
        }
        /** 工程自身的public.txt */
        def publicEntries = SymbolParser.getResourceEntries(small.publicSymbolFile)
        /** 工程自身的R.txt：AAPT编译产物，基于他过滤出retianedEntries */
        def bundleEntries = SymbolParser.getResourceEntries(idsFile)
        /** 所有修改的资源ID映射表：IdMaps<bundleId, libId> */
        def staticIdMaps = [:]
        def staticIdStrMaps = [:]
        /** 工程需要保留的资源：中间产物，目的是生成retainedTypes这个最终产物 */
        def retainedEntries = []
        /** 工程需要保留的public资源：对应工程自身的Public.txt */
        def retainedPublicEntries = []
        def retainedStyleables = []
        def reservedKeys = getReservedResourceKeys()
        Log.success "reservedKeys($reservedKeys)"

        bundleEntries.each { k, Map be ->
            be._typeId = UNSET_TYPEID // for sort
            be._entryId = UNSET_ENTRYID

            // 实现public固定资源ID: 用public.txt，替换R.txt; 以便后续重新javaC生成R.java
            // Q: 为啥不替换PP段？ 固定的资源ID呀！
            // A: 因为是bundle工程自身的资源，PP段，肯定就是bundle的packageId；所以，这里就不处理了？
            Map le = publicEntries.get(k)
            if (le != null) {
                // Use last built id
                be._typeId = le.typeId
                be._entryId = le.entryId
                retainedPublicEntries.add(be)
                publicEntries.remove(k)
                return
            }

            // 添加到保留资源列表
            if (reservedKeys.contains(k)) {
                be.isStyleable ? retainedStyleables.add(be) : retainedEntries.add(be)
                return
            }

            // 记录所有在lib库的public.txt内的资源ID
            le = libEntries.get(k)
            if (le != null) {
                // Add static id maps to host or library resources and map it later at
                // compile-time with the aapt-generated `resources.arsc' and `R.java' file
                staticIdMaps.put(be.id, le.id)
                staticIdStrMaps.put(be.idStr, le.idStr)
                return
            }

            // TODO: handle the resources addition by aar version conflict or something
//            if (be.type != 'id') {
//                throw new Exception(
//                        "Missing library resource entry: \"$k\", try to cleanLib and buildLib.")
//            }

            // Q: 为什么最后还需要再添加呢？
            be.isStyleable ? retainedStyleables.add(be) : retainedEntries.add(be)
        }

        // 保留所有已经被删除的public.txt资源: 前面已经过滤过，剩余的就是已经被bundle删除掉的.
        // Q: 这种也需要保留么？
        // TODO: retain deleted public entries
        if (publicEntries.size() > 0) {
            publicEntries.each { k, e ->
                e._typeId = e.typeId
                e._entryId = e.entryId
                e.entryId = Aapt.ID_DELETED

                def re = retainedPublicEntries.find{it.type == e.type}
                e.typeId = (re != null) ? re.typeId : Aapt.ID_DELETED
            }
            publicEntries.each { k, e ->
                retainedPublicEntries.add(e)
            }
        }

        // 如果没有需要retained的资源，则直接退出
        if (retainedEntries.size() == 0 && retainedPublicEntries.size() == 0) {
            small.retainedTypes = [] // Doesn't have any resources
            return
        }

        // Prepare public types : 准备Public.txt资源，为后续重新分配资源ID
        def publicTypes = [:]
        def maxPublicTypeId = 0
        def unusedTypeIds = [] as Queue
        if (retainedPublicEntries.size() > 0) {
            retainedPublicEntries.each { e ->
                def typeId = e._typeId
                def entryId = e._entryId
                def type = publicTypes[e.type]
                if (type == null) {
                    publicTypes[e.type] = [id: typeId, maxEntryId: entryId,
                                           entryIds:[entryId], unusedEntryIds:[] as Queue]
                    maxPublicTypeId = Math.max(typeId, maxPublicTypeId)
                } else {
                    type.maxEntryId = Math.max(entryId, type.maxEntryId)
                    type.entryIds.add(entryId)
                }
            }
            if (maxPublicTypeId != publicTypes.size()) {
                for (int i = 1; i < maxPublicTypeId; i++) {
                    if (publicTypes.find{ k, t -> t.id == i } == null) unusedTypeIds.add(i)
                }
            }
            publicTypes.each { k, t ->
                if (t.maxEntryId != t.entryIds.size()) {
                    for (int i = 0; i < t.maxEntryId; i++) {
                        if (!t.entryIds.contains(i)) t.unusedEntryIds.add(i)
                    }
                }
            }
        }

        // First sort with origin(full) resources order
        retainedEntries.sort { a, b ->
            a.typeId <=> b.typeId ?: a.entryId <=> b.entryId
        }

        // 重新分配资源ID：因为public.txt内的资源会随机占用了资源ID段; 会与原始AAPT产物有冲突;
        // 这一步会合并 etainedEntries += retainedPublicEntries
        // Reassign resource type id (_typeId) and entry id (_entryId)
        def lastEntryIds = [:]
        if (retainedEntries.size() > 0) {
            if (retainedEntries[0].type != 'attr') {
                // reserved for `attr'
                if (maxPublicTypeId == 0) maxPublicTypeId = 1
                if (unusedTypeIds.size() > 0) unusedTypeIds.poll()
            }
            def selfTypes = [:]
            retainedEntries.each { e ->
                // Check if the type has been declared in public.txt
                def type = publicTypes[e.type]
                if (type != null) {
                    e._typeId = type.id
                    if (type.unusedEntryIds.size() > 0) {
                        e._entryId = type.unusedEntryIds.poll()
                    } else {
                        e._entryId = ++type.maxEntryId
                    }
                    return
                }
                // Assign new type with unused type id
                type = selfTypes[e.type]
                if (type != null) {
                    e._typeId = type.id
                } else {
                    if (unusedTypeIds.size() > 0) {
                        e._typeId = unusedTypeIds.poll()
                    } else {
                        e._typeId = ++maxPublicTypeId
                    }
                    selfTypes[e.type] = [id: e._typeId]
                }
                // Simply increase the entry id
                def entryId = lastEntryIds[e.type]
                if (entryId == null) {
                    entryId = 0
                } else {
                    entryId++
                }
                e._entryId = lastEntryIds[e.type] = entryId
            }

            retainedEntries += retainedPublicEntries
        } else {
            retainedEntries = retainedPublicEntries
        }

        // 按照typeId -> entryId 顺序排列，以方便后续生成retainedTypes
        // Resort with reassigned resources order
        retainedEntries.sort { a, b ->
            a._typeId <=> b._typeId ?: a._entryId <=> b._entryId
        }

        // 前面处理完所有资源Entries后，开始生成retainedTypes(这个是最终产物)
        // Resort retained resources
        def retainedTypes = []  // List<Map<,,,,Map<>>>
        def pid = (small.packageId << 24)
        def currType = null
        retainedEntries.each { e ->
            // Prepare entry id maps for resolving resources.arsc and binary xml files
            if (currType == null || currType.name != e.type) {
                // New type
                currType = [type: e.vtype, name: e.type, id: e.typeId, _id: e._typeId, entries: []]
                retainedTypes.add(currType)
            }
            // 记录所有IdMaps: 后续，修改XML资源ID时，需要用到
            def newResId = pid | (e._typeId << 16) | e._entryId
            def newResIdStr = "0x${Integer.toHexString(newResId)}"
            staticIdMaps.put(e.id, newResId)    // 前面已经处理过libEntries(肯定是public固定的), 这里处理剩余的工程自身的ID，因此需要在分配资源后再处理;
            staticIdStrMaps.put(e.idStr, newResIdStr)

            // Q: 不大懂？
            // Prepare styleable id maps for resolving R.java
            if (retainedStyleables.size() > 0 && e.typeId == 1) {
                retainedStyleables.findAll { it.idStrs != null }.each {
                    // Replace `e.idStr' with `newResIdStr'
                    def index = it.idStrs.indexOf(e.idStr)
                    if (index >= 0) {
                        it.idStrs[index] = newResIdStr
                        it.mapped = true
                    }
                }
            }

            def entry = [name: e.key, id: e.entryId, _id: e._entryId, v: e.id, _v:newResId,
                         vs: e.idStr, _vs: newResIdStr]
            currType.entries.add(entry)
        }

        // Q: 不懂
        // Update the id array for styleables
        retainedStyleables.findAll { it.mapped != null }.each {
            it.idStr = "{ ${it.idStrs.join(', ')} }"
            it.idStrs = null
        }

        // Collect all the resources for generating a temporary full edition R.java
        // which required in javac.
        // TODO: Do this only for the modules who's code really use R.xx of lib.*
        def allTypes = []
        def allStyleables = []
        def addedTypes = [:]
        libEntries.each { k, e ->
            if (reservedKeys.contains(k)) return

            if (e.isStyleable) {
                allStyleables.add(e);
            } else {
                if (!addedTypes.containsKey(e.type)) {
                    // New type
                    currType = [type: e.vtype, name: e.type, entries: []]
                    allTypes.add(currType)
                    addedTypes.put(e.type, currType)
                } else {
                    currType = addedTypes[e.type]
                }

                def entry = [name: e.key, _vs: e.idStr]
                currType.entries.add(entry)
            }
        }
        retainedTypes.each { t ->
            def at = addedTypes[t.name]
            if (at != null) {
                at.entries.addAll(t.entries)
            } else {
                allTypes.add(t)
            }
        }
        allStyleables.addAll(retainedStyleables)

        // Collect vendor types and styleables
        def vendorEntries = [:]
        def vendorStyleableKeys = [:]
        transitiveVendorAars.each { aar ->
            String path = aar.path
            String resPath = new File(small.aarDir, path + '/res').absolutePath
            Set<Map> resTypeEntries = []
            Set<String> resStyleableKeys = []

            // Collect the resource entries declared in the aar res directory
            collectReservedResourceKeys(aar.version, resPath, resTypeEntries, resStyleableKeys)

            vendorEntries.put(path, resTypeEntries)
            vendorStyleableKeys.put(path, resStyleableKeys)
        }

        def vendorTypes = [:]
        def vendorStyleables = [:]
        vendorEntries.each { name, es ->
            if (es.isEmpty()) return

            allTypes.each { t ->
                t.entries.each { e ->
                    def ve = es.find { it.type == t.name && it.name == e.name }
                    if (ve != null) {
                        def vendorType
                        def vts = vendorTypes[name]
                        if (vts == null) {
                            vts = vendorTypes[name] = []
                        } else {
                            vendorType = vts.find { it.name == t.name }
                        }
                        if (vendorType == null) {
                            vendorType = [:]
                            vendorType.putAll(t)
                            vendorType.entries = []
                            vts.add(vendorType)
                        }
                        vendorType.entries.add(e)
                    }
                }
            }
        }
        vendorStyleableKeys.each { name, vs ->
            if (vs.isEmpty()) return

            allStyleables.each { s ->
                if (vs.contains(s.key)) {
                    if (vendorStyleables[name] == null) {
                        vendorStyleables[name] = []
                    }
                    vendorStyleables[name].add(s)
                    return
                }
            }
        }

        small.idMaps = staticIdMaps
        small.idStrMaps = staticIdStrMaps
        small.retainedTypes = retainedTypes
        small.retainedStyleables = retainedStyleables

        small.allTypes = allTypes
        small.allStyleables = allStyleables

        // 这一块，不太懂，用处是什么
        small.vendorTypes = vendorTypes
        small.vendorStyleables = vendorStyleables

        Log.success "prepareSplit: retainedTypes(${small.retainedTypes}) \n        retainedStyleables($retainedStyleables) \n        " +
                "vendorTypes($vendorTypes)"   //idMaps($staticIdStrMaps), 特别多的值！
    }

    protected int getABIFlag() {
        def abis = []

        def jniDirs = android.sourceSets.main.jniLibs.srcDirs
        if (jniDirs == null) jniDirs = []
        // Collect ABIs from AARs
        small.explodeAarDirs.each { dir ->
            File jniDir = new File(dir, 'jni')
            if (!jniDir.exists()) return
            jniDirs.add(jniDir)
        }
        def filters = android.defaultConfig.ndkConfig.abiFilters
        jniDirs.each { dir ->
            dir.listFiles().each { File d ->
                if (d.isFile()) return

                def abi = d.name
                if (filters != null && !filters.contains(abi)) return
                if (abis.contains(abi)) return

                abis.add(abi)
            }
        }

        return JNIUtils.getABIFlag(abis)
    }

    protected void hookVariantTask(BaseVariant variant) {
        hookMergeAssets(variant.mergeAssets)

//        hookRes(variant.mergeResources)

        hookProcessManifest(small.processManifest)

        hookAapt(small.aapt)

        hookJavac(small.javac, variant.buildType.minifyEnabled)

        // Q: 为啥没有看到，删除公共lib下的class类的代码？
        // 参考StripAarTransform 实现！

        // Hook clean task to unset package id
        project.clean.doLast {
            sPackageIds.remove(project.name)
        }
    }

    /**
     * Hook MergeResources task to ingnores the lib.* res
     * @param mergeResTask
     */
    private void hookRes(MergeResources mergeResTask) {
        mergeResTask.doFirst { MergeResources it ->
            // outputDir: build\intermediates\res\merged\release\
            // input($it.inputResourceSets),
            Log.header "[${project.name}] ----[Begin]----- MergeResources($it.name)" +
                    ";  output($it.outputDir)"

            def stripPaths = new HashSet<File>()
            mergeResTask.inputResourceSets.each {
                // sourceFiles: intermediates\exploded-aar\com.android.support\support-v4\23.2.1\res 可以排除掉！
//                Log.success "[${project.name}] sourceFiles($it.sourceFiles), configName($it.configName)"

                // configName: 对于aar，就是版本号(23.2.1)；对于Module，一般都是(main, release)
                if (it.configName == 'main' || it.configName == 'release') return

                // build\intermediates\exploded-aar\[packagename\name\version]\res\
                it.sourceFiles.each {
                    def version = it.parentFile
                    def name = version.parentFile
                    def group = name.parentFile
                    def aar = [group: group.name, name: name.name, version: version.name]

                    // 如果非普通compile依赖，则需要过滤掉这个aar包的资源！ --即ProvidedCompile的概念
                    // 注：res需要参与AAPT的编译过程， 所以不能过滤掉任何依赖资源！
                    if (!mUserLibAars.contains(aar)) {
                    // 排除所有lib库的res文件
//                    if (it.name.contains(':lib.')) {
//                        stripPaths.add(it)
                    }
                }
            }
            Log.success "[${project.name}] split library res files... $stripPaths"

            // 重命名ProvidedCompile的aar内的res/
            def filteredRes = []
            stripPaths.each {
                def backup = new File(it.parentFile, "$it.name~")
                filteredRes.add(org: it, backup: backup)
                it.renameTo(backup)
//                Log.success "[${project.name}] rename($it) to($backup)"
            }

            // 通过扩展的方式，添加成员变量，以便doLast时，能够恢复;
            it.extensions.add('filteredRes', filteredRes)
        }

        // 重新恢复ProvidedAar
        mergeResTask.doLast {
            Set<Map> filteredRes = (Set<Map>) it.extensions.getByName('filteredRes')
            filteredRes.each {
                it.backup.renameTo(it.org)
            }

            Log.footer "[${project.name}] gen MergeRes($mergeResTask.outputDir)"
        }

        applyPublicXml(mergeResTask)
    }

    /**
     * 应用public.xml
     */
    private void applyPublicXml(MergeResources mergeResTask) {
        mergeResTask.doLast {
            Log.header "[${project.name}] applyPublicXml, mergeResTask($mergeResTask.name)"
            project.copy {
                int i = 0
                from(android.sourceSets.main.res.srcDirs) {
                    include 'values/public.xml'
                    rename 'public.xml', (i++ == 0 ? "public.xml" : "public_${i}.xml")
                }
                into(mergeResTask.outputDir)
            }
            Log.success "[${project.name}] copy ($android.sourceSets.main.res.srcDirs) to ($mergeResTask.outputDir)"
        }
    }

            /**
     * Hook merge-assets task to ignores the lib.* assets
     * TODO: filter the assets while exploding aar
     * @param mergeAssetsTask
     */
    private void hookMergeAssets(MergeSourceSetFolders mergeAssetsTask) {
        mergeAssetsTask.doFirst { MergeSourceSetFolders it ->

            Log.header "[${project.name}] mergeAssetsTask($mergeAssetsTask.name) MergeSourceSetFolders($it.name) "

            def stripPaths = new HashSet<File>()
            mergeAssetsTask.inputDirectorySets.each {
//                Log.success "[${project.name}] check sourceFiles($it.sourceFiles) configName($it.configName)"

                // configName: 对于aar，就是版本号(23.2.1)；对于Module，一般都是(main, release)
                if (it.configName == 'main' || it.configName == 'release') return

                // build\intermediates\exploded-aar\[packagename]\assets\
                it.sourceFiles.each {
                    def version = it.parentFile
                    def name = version.parentFile
                    def group = name.parentFile
                    def aar = [group: group.name, name: name.name, version: version.name]
                    // 如果非普通compile依赖，则需要过滤掉这个aar包的资源！ --即ProvidedCompile的概念
                    if (!mUserLibAars.contains(aar)) {
                        stripPaths.add(it)
                    }
                }
            }
            Log.success "[${project.name}] split library assets files... $stripPaths"

            // 重命名ProvidedCompile的aar
            def filteredAssets = []
            stripPaths.each {
                def backup = new File(it.parentFile, "$it.name~")
                filteredAssets.add(org: it, backup: backup)
                it.renameTo(backup)
            }

            // 通过扩展的方式，添加成员变量，以便doLast时，能够恢复;
            it.extensions.add('filteredAssets', filteredAssets)
        }

        // 以便doLast时，恢复ProvidedCompile的aar;
        mergeAssetsTask.doLast {
            Set<Map> filteredAssets = (Set<Map>) it.extensions.getByName('filteredAssets')
            filteredAssets.each {
                it.backup.renameTo(it.org)
            }

            Log.footer "[${project.name}] gen MergeAssets($mergeAssetsTask.outputDir)"
        }
    }

    protected static void collectAars(File d, Project src, Set outAars) {
        d.eachLine { line ->
            def module = line.split(':')
            def N = module.size()
            def aar = [group: module[0], name: module[1], version: (N == 3) ? module[2] : '']
            if (!outAars.contains(aar)) {
                outAars.add(aar)
            }
        }
    }

    @Override
    protected void hookPreReleaseBuild() {
        super.hookPreReleaseBuild()

        // Ensure generating text symbols - R.txt
        // --------------------------------------
        def symbolsPath = small.aapt.textSymbolOutputDir.path
        android.aaptOptions.additionalParameters '--output-text-symbols', symbolsPath

        // Resolve dependent AARs
        // ----------------------
        def smallLibAars = new HashSet() // the aars compiled in host or lib.*

        // Collect aar(s) in lib.*
        mDependentLibProjects.each { lib ->
            // lib.* dependencies
            File file = new File(rootSmall.preLinkAarDir, "$lib.name-D.txt")
            collectAars(file, lib, smallLibAars)

            // lib.* self
            smallLibAars.add(group: lib.group, name: lib.name, version: lib.version)
        }

        // Collect aar(s) in host
        File hostAarDependencies = new File(rootSmall.preLinkAarDir, "$rootSmall.hostModuleName-D.txt")
        collectAars(hostAarDependencies, rootSmall.hostProject, smallLibAars)

        small.splitAars = smallLibAars
        small.retainedAars = mUserLibAars
    }

    private def hookProcessManifest(Task processManifest) {
        // 去除所有ProvidedCompile的任务
        // If an app.A dependent by lib.B and both of them declare application@name in their
        // manifests, the `processManifest` task will raise an conflict error.
        // Cause the release mode doesn't need to merge the manifest of lib.*, simply split
        // out the manifest dependencies from them.
        processManifest.doFirst { MergeManifests it ->
            if (pluginType != PluginType.App) return

            Log.header "[${project.name}] processManifest($processManifest.name) MergeManifestsTask($it.name) Task.project($it.project.name)"

            // 从遍历每一个Task的compile依赖，排除lib库
            def libs = it.libraries
            def smallLibs = []
            libs.each {
                // \build\intermediates\exploded-aar\
                // Q: 为啥没有com.android.support/下的Manifest文件呢？
                Log.success "[${project.name}] check MergeManifestsTask.libraries($it.name)"
                // 排除所有lib库的Manifest文件
                if (it.name.contains(':lib.')) {
                    smallLibs.add(it)

                    Log.success "[${project.name}] split library Manifest files... $it.name, file($it.manifest.absolutePath)"
                }

            }
            libs.removeAll(smallLibs)
            it.libraries = libs
        }

        // 解决Manifest合并时的错误！
        // Q: 上一步都去除了，这里为啥还会有报错！
        // Hook process-manifest task to remove the `android:icon' and `android:label' attribute
        // which declared in the plugin `AndroidManifest.xml' application node. (for #11)
        processManifest.doLast { MergeManifests it ->
            // build\intermediates\manifests\full\
            File manifestFile = it.manifestOutputFile
            def sb = new StringBuilder()
            def enteredApplicationNode = false
            def needsFilter = true
            def filterKeys = [
                    'android:icon', 'android:label',
                    'android:allowBackup', 'android:supportsRtl'
            ]

            Log.footer "[${project.name}] gen manifestOutputFile($manifestFile)"

            // We don't use XmlParser but simply parse each line cause this should be faster
            manifestFile.eachLine { line ->
                while (true) { // fake loop for less `if ... else' statement
                    if (!needsFilter) break

                    def i = line.indexOf('<application')
                    if (i < 0) {
                        if (!enteredApplicationNode) break

                        int endPos = line.indexOf('>')
                        if (endPos > 0) needsFilter = false

                        // filter unused keys
                        def filtered = false
                        filterKeys.each {
                            if (line.indexOf(it) > 0) {
                                filtered = true
                                return
                            }
                        }
                        if (filtered) {
                            if (needsFilter) return

                            if (line.charAt(endPos - 1) == '/' as char) {
                                line = '/>'
                            } else {
                                line = '>'
                            }
                        }
                        break
                    }

                    def j = line.indexOf('<!--')
                    if (j > 0 && j < i) break // ignores the comment line

                    if (line.indexOf('>') > 0) { // <application /> or <application .. > in one line
                        needsFilter = false
                        break
                    }

                    enteredApplicationNode = true // mark this for next line
                    break
                }

                sb.append(line).append(System.lineSeparator())
            }
            manifestFile.write(sb.toString(), 'utf-8')
        }
    }

    // 固定lib资源ID：修改R.java，重新编译ApplicationModule的所有class文件；
    // 如何修复arsc文件，所有XML编译文件呢？ -- 没有看懂！
    /**
     * Hook aapt task to slice asset package and resolve library resource ids
     */
    private def hookAapt(ProcessAndroidResources aaptTask) {
        aaptTask.doLast { ProcessAndroidResources it ->
            Log.header "[${project.name}] hookAapt aaptTask($aaptTask.name), ProcessAndroidResources($it.name): " +
                    "packageOutputFile($it.packageOutputFile), textSymbolOutputDir($it.textSymbolOutputDir) "

            // Unpack resources.ap_ : \build\intermediates\res\resources-release.ap_
            File apFile = it.packageOutputFile
            FileTree apFiles = project.zipTree(apFile)
            File unzipApDir = new File(apFile.parentFile, 'ap_unzip')
            unzipApDir.delete()
            project.copy {
                from apFiles
                into unzipApDir

                include 'AndroidManifest.xml'
                include 'resources.arsc'
                include 'res/**/*'
            }

            // Debug, bake apFile to compare two apFiles
//            copyFile(apFile, apFile.getParent(), apFile.name + ".bak")
            org.apache.commons.io.FileUtils.copyFile(apFile, new File(apFile.getParent(), apFile.name + ".bak"))

            // Modify assets : 为啥最终生成的apFile，会比原始的多了一个assets呢？
            prepareSplit()

            // 工程合成后的R.txt文件: 为啥业务插件，不传递R.txt, 不需要固定public.txt么？
            // symbolFile: build\intermediates\symbols\release\R.txt
            File symbolFile = (small.type == PluginType.Library) ?
                    new File(it.textSymbolOutputDir, 'R.txt') : null
            // 工程合成后的R.java文件
            File sourceOutputDir = it.sourceOutputDir
            File rJavaFile = new File(sourceOutputDir, "${small.packagePath}/R.java")
            def rev = android.buildToolsRevision
            int noResourcesFlag = 0
            def filteredResources = new HashSet()
            def updatedResources = new HashSet()
            Aapt aapt = new Aapt(unzipApDir, rJavaFile, symbolFile, rev)
            Log.success "[${project.name}] ReAapt symbolFile($symbolFile), rJavaFile($rJavaFile) unzipApDir($unzipApDir)"

            if (small.retainedTypes != null && small.retainedTypes.size() > 0) {
                // 这两段难理解; 只能反推; 屏蔽调后，看看打包结果如何，对比就能看出来作用！
                // 过滤res/目录：排除掉filteredResources资源
                aapt.filterResources(small.retainedTypes, filteredResources)
                Log.success "[${project.name}] split library res files..."

                // 修改资源ID：处理resources.arsc文件、XML文件、R.txt
                aapt.filterPackage(small.retainedTypes, small.packageId, small.idMaps,
                        small.retainedStyleables, updatedResources)
                Log.success "[${project.name}] slice asset package and reset package id...${small.packageId}, updatedResources($updatedResources)"   //, idMaps($small.idMaps)

                String pkg = small.packageName

                // 重新生成R.java: 固定publicID, 后续javac编译class文件时，会应用到该类；
                // Overwrite the aapt-generated R.java with full edition: build\generated\source\r\release\net\wequick\example\small\app\mine\R.java
                aapt.generateRJava(small.rJavaFile, pkg, small.allTypes, small.allStyleables)
                Log.success "[${project.name}] generateRJava($small.rJavaFile)"

                // 生成small.splitRJavaFile文件: 仅插件自身资源ID；
                // 关键：该文件，最终打包到dex中！
                // build\generated\source\r\small\net\wequick\example\small\app\mine\R.java
                // Also generate a split edition for later re-compiling
                aapt.generateRJava(small.splitRJavaFile, pkg,
                        small.retainedTypes, small.retainedStyleables)
                Log.success "[${project.name}] generate split RJava($small.splitRJavaFile)"

                // 场景：依赖的aar内，代码访问R.资源
                // Overwrite the retained vendor R.java
                def retainedRFiles = [small.rJavaFile]
                small.vendorTypes.each { name, types ->
                    // 找到aar包，解析出包名
                    File aarDir = new File(small.aarDir, name)
                    File manifestFile = new File(aarDir, 'AndroidManifest.xml')
                    def manifest = new XmlParser().parse(manifestFile)
                    String aarPkg = manifest.@package
                    String pkgPath = aarPkg.replaceAll('\\.', '/')
                    File r = new File(sourceOutputDir, "$pkgPath/R.java")
                    retainedRFiles.add(r)

                    def styleables = small.vendorStyleables[name]
                    aapt.generateRJava(r, aarPkg, types, styleables)
                    Log.success "[${project.name}] ----------Overwrite the retained vendor($name) R.java($r.path)"
                }

                // Remove unused R.java to fix the reference of shared library resource, issue #63
                sourceOutputDir.eachFileRecurse(FileType.FILES) { file ->
                    if (!retainedRFiles.contains(file)) {
                        file.delete()
                    }
                }

                Log.success "[${project.name}] split library R.java files..."
            } else {
                // 如果插件自身没有任何资源：则简单删除所有资源
                noResourcesFlag = 1
                if (aapt.deleteResourcesDir(filteredResources)) {
                    Log.success "[${project.name}] remove resources dir..."
                }

                if (aapt.deletePackage(filteredResources)) {
                    Log.success "[${project.name}] remove resources.arsc..."
                }

                if (small.rJavaFile.delete()) {
                    Log.success "[${project.name}] remove R.java..."
                }

                small.symbolFile.delete() // also delete the generated R.txt
            }

            // 完全不懂!
            int abiFlag = getABIFlag()
            int flags = (abiFlag << 1) | noResourcesFlag
            if (aapt.writeSmallFlags(flags, updatedResources)) {
                Log.success "[${project.name}] add flags: ${Integer.toBinaryString(flags)}..."
            }

            String aaptExe = small.aapt.buildTools.getPath(BuildToolInfo.PathId.AAPT)

            // 更新AAPT结果：先删除，再重新添加；因为没有update命令；
            //      添加文件，不涉及到编译过程，仅仅是更新压缩内的文件？  -- 所以，此时，清理资源是不会有影响的！
            //      问题：为啥不是对unzip解压&过滤&修改过的文件，重新压缩下 即可？ 为啥要调用AAPT工具，重新对.ap_压缩包处理？
            // 这一步更新*.ap_文件(包括assets/, res/, Manifest.xml, resource.arsc文件)
            // Delete filtered entries.
            // Cause there is no `aapt update' command supported, so for the updated resources
            // we also delete first and run `aapt add' later.
            filteredResources.addAll(updatedResources)  // 添加Resource.arsc文件，各种有依赖lib资源的XML文件，也需要重新更新
            ZipUtils.with(apFile).deleteAll(filteredResources)      //Q: unzip解压包中，已经过滤掉了，为啥还需要再处理.ap_压缩包呢？

            // Re-add updated entries.
            // $ aapt add resources.ap_ file1 file2 ...
            project.exec {
                executable aaptExe
                workingDir unzipApDir
                args 'add', apFile.path
                args updatedResources

                // store the output instead of printing to the console
                standardOutput = new ByteArrayOutputStream()
            }
        }
    }


    /**
     * Hook javac task to split libraries' R.class <br/>
     * 重新编译个lib的R.class文件，以便能应用修改了的资源ID;
     * 由于没有lib库没有静态内联优化，因此无需重新编译其他class文件！
     */
    private def hookJavac(Task javac, boolean minifyEnabled) {
        javac.doFirst { JavaCompile it ->
            // Dynamically provided jars
            it.classpath += project.files(getLibraryJars())

//            Log.header "[${project.name}] hookJavac Dynamically provided jars($it.classpath.asPath)" +
//                    " input($it.source.asPath) output($it.destinationDir)"
        }

        javac.doLast { JavaCompile it ->
            if (minifyEnabled) return // process later in proguard task
            if (!small.splitRJavaFile.exists()) return

            File classesDir = it.destinationDir
            File dstDir = new File(classesDir, small.packagePath)

            // Delete the original generated R$xx.class
            dstDir.listFiles().each { f ->
                if (f.name.startsWith('R$')) {
                    f.delete()
//                    Log.success "[${project.name}] deleteFile($f)"
                }
            }
            // Re-compile the split R.java to R.class
            project.ant.javac(srcdir: small.splitRJavaFile.parentFile,
                    source: it.sourceCompatibility,
                    target: it.targetCompatibility,
                    destdir: classesDir)

            // form build\generated\source\r\small\net\wequick\example\small\app\mine\R.java to  build\intermediates\classes\release
            Log.success "[${project.name}] Re-compile the split R.java($small.splitRJavaFile) to destdir($classesDir)"
        }
    }

    /**
     * Get reserved resource keys of project. For making a smaller slice, the unnecessary
     * resource `mipmap/ic_launcher' and `string/app_name' are excluded.
     */
    protected def getReservedResourceKeys() {
        Set<Map> outTypeEntries = []
        Set<String> outStyleableKeys = []
        collectReservedResourceKeys(null, null, outTypeEntries, outStyleableKeys)
        def keys = []
        outTypeEntries.each {
            keys.add("$it.type/$it.name")
        }
        outStyleableKeys.each {
            keys.add("styleable/$it")
        }
        return keys
    }

    /**
     *
     * @param config
     * @param path
     * @param outTypeEntries List<Map<type: type, name: name>>
     * @param outStyleableKeys
     */
    protected void collectReservedResourceKeys(config, path, outTypeEntries, outStyleableKeys) {
        // 解析mergerXml文件: 通过config过滤出需要的资源包；默认情况下，仅本工程的资源！
        def merger = new XmlParser().parse(small.mergerXml)
        def filter = config == null ? {
            it.@config == 'main' || it.@config == 'release'
        } : {
            it.@config = config
        }

        def dataSets = merger.dataSet.findAll filter
        dataSets.each { // <dataSet config="main" generated-set="main$Generated">
            it.source.each { // <source path="**/${project.name}/src/main/res">
                if (path != null && it.@path != path) return

                it.file.each {
                    def type = it.@type
                    if (type != null) { // <file name="activity_main" ... type="layout"/>
                        def name = it.@name
                        if (type == 'mipmap' && name == 'ic_launcher') return // NO NEED IN BUNDLE
                        def key = [type: type, name: name] // layout/activity_main
                        if (!outTypeEntries.contains(key)) outTypeEntries.add(key)
                        return
                    }

                    // 如果没有type字段：继续遍历，children.name 来判断类型
                    it.children().each {
                        type = it.name()
                        def name = it.@name
                        if (type == 'string') {
                            if (name == 'app_name') return // DON'T NEED IN BUNDLE
                        } else if (type == 'style') {
                            name = name.replaceAll("\\.", "_")
                        } else if (type == 'declare-styleable') {
                            // <declare-styleable name="MyTextView">
                            it.children().each { // <attr format="string" name="label"/>
                                def attr = it.@name
                                if (attr.startsWith('android:')) {
                                    attr = attr.replaceAll(':', '_')
                                } else {
                                    def key = [type: 'attr', name: attr]
                                    if (!outTypeEntries.contains(key)) outTypeEntries.add(key)
                                }
                                String key = "${name}_${attr}"
                                if (!outStyleableKeys.contains(key)) outStyleableKeys.add(key)
                            }
                            if (!outStyleableKeys.contains(name)) outStyleableKeys.add(name)
                            return
                        } else if (type.endsWith('-array')) {
                            // string-array or integer-array
                            type = 'array'
                        }

                        def key = [type: type, name: name]
                        if (!outTypeEntries.contains(key)) outTypeEntries.add(key)
                    }
                }
            }
        }

//        Log.success "collectReservedResourceKeys: mergerXml($small.mergerXml), outTypeEntries($outTypeEntries), \n outStyleableKeys($outStyleableKeys)";
    }

    /**
     * Init package id for bundle, if has not explicitly set in 'build.gradle' or
     * 'gradle.properties', generate a random one
     */
    protected void initPackageId() {
        Integer pp
        String ppStr = null
        Integer usingPP = sPackageIds.get(project.name)
        boolean addsNewPP = true
        // Get user defined package id
        if (project.hasProperty('packageId')) {
            def userPP = project.packageId
            if (userPP instanceof Integer) {
                // Set in build.gradle with 'ext.packageId=0x7e' as an Integer
                pp = userPP
            } else {
                // Set in gradle.properties with 'packageId=7e' as a String
                ppStr = userPP
                pp = Integer.parseInt(ppStr, 16)
            }

            if (usingPP != null && pp != usingPP) {
                // TODO: clean last build
                throw new Exception("Package id for ${project.name} has changed! " +
                        "You should call clean first.")
            }
        } else {
            if (usingPP != null) {
                pp = usingPP
                addsNewPP = false
            } else {
                pp = genRandomPackageId(project.name)
            }
        }

        small.packageId = pp
        small.packageIdStr = ppStr != null ? ppStr : String.format('%02x', pp)
        if (!addsNewPP) return

        // Check if the new package id has been used
        sPackageIds.each { name, id ->
            if (id == pp) {
                throw new Exception("Duplicate package id 0x${String.format('%02x', pp)} " +
                        "with $name and ${project.name}!\nPlease redefine one of them " +
                        "in build.gradle (e.g. 'ext.packageId=0x7e') " +
                        "or gradle.properties (e.g. 'packageId=7e').")
            }
        }
        sPackageIds.put(project.name, pp)
    }

    /**
     * Generate a random package id in range [0x03, 0x7e] by bundle's name.
     * [0x00, 0x02] reserved for android system resources.
     * [0x03, 0x0f] reserved for the fucking crazy manufacturers.
     */
    private static int genRandomPackageId(String bundleName) {
        int minPP = 0x10
        int maxPP = 0x7e
        int maxHash = 0xffff
        int d = maxPP - minPP
        int hash = bundleName.hashCode() & maxHash
        int pp = (hash * d / maxHash) + minPP
        return pp
    }

    public void copyFile(File file, String destPath, String fileName) {
        project.copy {
            from file
            to destPath
            rename(file.getName(), fileName)
        }
    }
}
