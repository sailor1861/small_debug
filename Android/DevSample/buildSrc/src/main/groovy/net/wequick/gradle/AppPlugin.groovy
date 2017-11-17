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
import com.android.build.gradle.tasks.MergeSourceSetFolders
import com.android.build.gradle.tasks.ProcessAndroidResources
import com.android.build.gradle.tasks.ProcessTestManifest
import com.android.sdklib.BuildToolInfo
import groovy.io.FileType
import net.wequick.gradle.aapt.Aapt
import net.wequick.gradle.aapt.SymbolParser
import net.wequick.gradle.transform.StripAarTransform
import net.wequick.gradle.util.*
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

    // 缓存全局的PackageIds：和工程名称绑定
    // todo: 自定义属性替代
    protected static def sPackageIds = [:] as LinkedHashMap<String, Integer>

    /** 插件依赖的lib插件工程 */
    protected Set<Project> mDependentLibProjects
    protected Set<Project> mTransitiveDependentLibProjects  // 等同于mProvidedProjects
    protected Set<Project> mProvidedProjects
    /** 插件依赖的普遍工程 */
    protected Set<Project> mCompiledProjects
    protected Set<Map> mUserLibAars
    /** 插件所有Provided依赖：只编译，不打包的依赖 */
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
    protected void afterEvaluate(boolean released) {
        super.afterEvaluate(released)

        // Initialize a resource package id for current bundle
        initPackageId()

        // 这里只能获取compile project('')的依赖，如何获取compile 'aar'的依赖
        // todo: 新增一个List<ProvidedAAR>， 用于填充aar依赖
        // Get all dependencies with gradle script `compile project(':lib.*')'
        DependencySet compilesDependencies = project.configurations.compile.dependencies

        // todo:  分析compile依赖， 需要新增compile 'aar'的依赖分析
        Set<DefaultProjectDependency> allLibs = compilesDependencies.withType(DefaultProjectDependency.class)
        Set<DefaultProjectDependency> smallLibs = []
        mUserLibAars = []
        mDependentLibProjects = []
        mProvidedProjects = []
        mCompiledProjects = []
        allLibs.each {
	        Log.success "[${project.name}] afterEvaluate：check compilesDependencies($it.dependencyProject.name)"
            // 区分lib库(公共库插件)和其他普通依赖库
            if (rootSmall.isLibProject(it.dependencyProject)) {
                smallLibs.add(it)
                mProvidedProjects.add(it.dependencyProject)
                mDependentLibProjects.add(it.dependencyProject)
            } else {
                mCompiledProjects.add(it.dependencyProject)
                collectAarsOfLibrary(it.dependencyProject, mUserLibAars)
            }
        }
        collectAarsOfLibrary(project, mUserLibAars)
        mProvidedProjects.addAll(rootSmall.hostStubProjects)    // 添加默认的hostStub依赖

        // 编译lib公共插件时，移除其他module对当前所有libs的依赖
        // 单独打lib包没有问；如果本地有App+Lib的工程模式，会出现warning!
        if (rootSmall.isBuildingLibs()) {
            // While building libs, `lib.*' modules are changing to be an application
            // module and cannot be depended by any other modules. To avoid warnings,
            // remove the `compile project(':lib.*')' dependencies temporary.
            compilesDependencies.removeAll(smallLibs)
        }

        if (!released) return

        // Add custom transformation to split shared libraries
        android.registerTransform(new StripAarTransform())

        resolveReleaseDependencies()
    }

    protected static def getJarName(Project project) {
        def group = project.group
        if (group == project.rootProject.name) group = project.name
        return "$group-${project.version}.jar"
    }

    protected static Set<File> getJarDependencies(Project project) {
        return project.fileTree(dir: 'libs', include: '*.jar').asList()
    }

    /**
     * 获取插件Provided jars
     * @return
     */
    protected Set<File> getLibraryJars() {
        if (mLibraryJars != null) return mLibraryJars

        mLibraryJars = new LinkedHashSet<File>()

        // Collect the jars in `build-small/intermediates/small-pre-jar/base'
        def baseJars = project.fileTree(dir: rootSmall.preBaseJarDir, include: ['*.jar'])
        mLibraryJars.addAll(baseJars.files)

        // Collect the jars of `compile project(lib.*)' with absolute file path, fix issue #65
        Set<String> libJarNames = []
        Set<File> libDependentJars = []
        mTransitiveDependentLibProjects.each {
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

        // todo: 需要移除;
        // 能否从其他地方获取？ -- 打hostStub时，缓存到buildCache
        // Collect stub and small jars in host
        Set<Project> sharedProjects = []
        sharedProjects.addAll(rootSmall.hostStubProjects)
        if (rootSmall.smallProject != null) {
            sharedProjects.add(rootSmall.smallProject)
        }
        sharedProjects.each {
            def jarTask = it.tasks.withType(TransformTask.class).find {
                it.variantName == 'release' && it.transform.name == 'syncLibJars'
            }
            if (jarTask != null) {
                mLibraryJars.addAll(jarTask.otherFileOutputs)
                Log.result "[getLibraryJars], mLibraryJars.addAll($jarTask.otherFileOutputs); from($rootSmall.hostStubProjects)"
            }
        }

        // todo: 能否，从其他地方获取，比如publicCache中？
        // 目前是空的：是否可以直接去除？
        rootSmall.hostProject.tasks.withType(TransformTask.class).each {
            if ((it.variantName == 'release' || it.variantName.contains("Release"))
                    && (it.transform.name == 'dex' || it.transform.name == 'proguard')) {
                Log.result "[getLibraryJars], add hostProject.TransformTask streamInputs: " + it.streamInputs.findAll { it.name.endsWith('.jar') } +
                        "; Before size:" + mLibraryJars.size()
                mLibraryJars.addAll(it.streamInputs.findAll { it.name.endsWith('.jar') })
                Log.result "[getLibraryJars], After size:" + mLibraryJars.size()
            }
        }

        /*
        宿主app:
                D:\code\Small\Android\DevSample\build-small\intermediates\small-pre-jar\base\app-r.jar,
        app+stub：
                D:\code\Small\Android\Sample\app+stub\build\intermediates\bundles\default\libs\assets.jar
                D:\code\Small\Android\DevSample\build-small\intermediates\small-pre-jar\libs\app+stub-unspecified.jar,
                D:\code\Small\Android\Sample\app+stub\libs\assets.jar,
                D:\code\Small\Android\Sample\app+stub\build\intermediates\bundles\default\classes.jar,
                D:\code\Small\Android\Sample\app+stub\build\intermediates\bundles\default\libs\assets.jar
        support包：
                D:\code\Small\Android\DevSample\build-small\intermediates\small-pre-jar\base\com.android.support-animated-vector-drawable-25.1.0.jar,

        todo：
             AH方案下， 插件已经通过Provied 依赖了fatJar包，是否可以省略宿主的jars, support前期也不支持;
             不过，更好的方式， 还是通过插件这里，实现自定Provided注入，更方便一键集成！
             此外，公共插件的jars, 还是需要通过这种方式注入的！
        */
//        Log.footer "project[$project] getLibraryJars$mLibraryJars)"
        return mLibraryJars
    }

    // 目的是？
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
    protected void hookPreDebugBuild() {
        super.hookPreDebugBuild()

        // If an app.A dependent by lib.B and both of them declare application@name in their
        // manifests, the `processManifest` task will raise a conflict error. To avoid this,
        // modify the lib.B manifest to remove the attributes before app.A `processManifest`
        // and restore it after the task finished.

        // processDebugManifest
        project.tasks.withType(MergeManifests.class).each {
            if (it.variantName.startsWith('release')) return

            if (it.hasProperty('providers')) {
                it.providers = []
                return
            }

            hookProcessDebugManifest(it, it.libraries)
        }

        // processDebugAndroidTestManifest
        project.tasks.withType(ProcessTestManifest.class).each {
            if (it.variantName.startsWith('release')) return

            if (it.hasProperty('providers')) {
                it.providers = []
                return
            }

            hookProcessDebugManifest(it, it.libraries)
        }
    }

    protected void collectLibManifests(def lib, Set outFiles) {
        outFiles.add(lib.getManifest())

        if (lib.hasProperty("libraryDependencies")) {
            // >= 2.2.0
            lib.getLibraryDependencies().each {
                collectLibManifests(it, outFiles)
            }
        } else {
            // < 2.2.0
            lib.getManifestDependencies().each {
                collectLibManifests(it, outFiles)
            }
        }
    }

    protected void hookProcessDebugManifest(Task processDebugManifest,
                                            List libs) {
        if (processDebugManifest.hasProperty('providers')) {
            processDebugManifest.providers = []
            return
        }

        processDebugManifest.doFirst {
            def libManifests = new HashSet<File>()
            libs.each {
                def components = it.name.split(':') // e.g. 'Sample:lib.style:unspecified'
                if (components.size() != 3) return

                def projectName = components[1]
                if (!rootSmall.isLibProject(projectName)) return

                Set<File> allManifests = new HashSet<File>()
                collectLibManifests(it, allManifests)

                libManifests.addAll(allManifests.findAll {
                    // e.g.
                    // '**/Sample/lib.style/unspecified/AndroidManifest.xml
                    // '**/Sample/lib.analytics/unspecified/AndroidManifest.xml
                    def name = it.parentFile.parentFile.name
                    rootSmall.isLibProject(name)
                })
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
            getLibraryJars().findAll{ it.exists() }.each {
                // FIXME: the `libraryJar' method is protected, may be depreciated
                pt.libraryJar(it)
            }
        }
        // Split R.class
        proguard.doLast {
            if (small.splitRJavaFile == null || !small.splitRJavaFile.exists()) {
                return
            }

            def minifyJar = IntermediateFolderUtils.getContentLocation(
                    proguard.streamOutputFolder, 'main', pt.outputTypes, pt.scopes, Format.JAR)
            if (!minifyJar.exists()) return

            mMinifyJar = minifyJar // record for `LibraryPlugin'

            Log.success("[$project.name] Strip aar classes...")

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

    // 收集所有Vendor.aar: compile project() + compile 'aar'
    /** Collect the vendor aars (has resources) compiling in current bundle */
    protected void collectVendorAars(Set<ResolvedDependency> outFirstLevelAars,
                                     Set<Map> outTransitiveAars) {
        project.configurations.compile.resolvedConfiguration.firstLevelModuleDependencies.each {
            collectVendorAars(it, outFirstLevelAars, outTransitiveAars)
        }
    }

    protected boolean collectVendorAars(ResolvedDependency node,
                                        Set<ResolvedDependency> outFirstLevelAars,
                                        Set<Map> outTransitiveAars) {
        def group = node.moduleGroup,
            name = node.moduleName,
            version = node.moduleVersion

        if (group == '' && version == '') {
            // Ignores the dependency of local aar
            return false
        }

        // todo: 过滤ProvidedCompile('aar')
        if (isProvidedAar()) {
            Log.action("collectVendorAars", "Check ResolvedDependency($node.name)")
            if (node.name.contains("com.example.mysmall.lib.style")) {
                Log.result("[collectVendorAars] Filter ResolvedDependency($node.name) fixed To ProvidedCompile")
                return false;
            }
        }

        // host、lib库中已经声明的依赖：需要排除掉
        if (small.splitAars.find { aar -> group == aar.group && name == aar.name } != null) {
            // Ignores the dependency which has declared in host or lib.*
//            Log.result("[collectVendorAars] Ignores the dependency which has declared in host or lib.*($node.name)")
            return false
        }
        // todo : 这里的过滤，是做什么?
        if (small.retainedAars.find { aar -> group == aar.group && name == aar.name } != null) {
            // Ignores the dependency of normal modules
            Log.result("collectVendorAars", "Ignores the dependency of normal modules($node.name)")
            return false
        }

        String path = "$group/$name/$version"
        def aar = [path: path, group: group, name: node.name, version: version]
        File aarOutput = small.buildCaches.get(path)
        if (aarOutput != null) {
            def resDir = new File(aarOutput, "res")
            // If the dependency has resources, collect it
            if (resDir.exists() && resDir.list().size() > 0) {
                if (outFirstLevelAars != null && !outFirstLevelAars.contains(node)) {
                    outFirstLevelAars.add(node)
                }
                if (!outTransitiveAars.contains(aar)) {
                    outTransitiveAars.add(aar)
                }
                node.children.each { next ->
                    collectVendorAars(next, null, outTransitiveAars)
                }
                return true
            }
        }

        // Otherwise, check it's children for recursively collecting
        boolean flag = false
        node.children.each { next ->
            flag |= collectVendorAars(next, null, outTransitiveAars)
        }
        if (!flag) return false

        if (outFirstLevelAars != null && !outFirstLevelAars.contains(node)) {
            outFirstLevelAars.add(node)
        }
        return true
    }

    protected void collectTransitiveAars(ResolvedDependency node,
                                         Set<ResolvedDependency> outAars) {
        def group = node.moduleGroup,
            name = node.moduleName

        if (small.splitAars.find { aar -> group == aar.group && name == aar.name } == null) {
            outAars.add(node)
        }

        node.children.each {
            collectTransitiveAars(it, outAars)
        }
    }

    // 特别关键：保证public.txt内的ID，能够固定下来的机制
    /**
     * Prepare retained resource types and resource id maps for package slicing
     */
    protected void prepareSplit() {
        // 判断本工程，是否有R文件，没有的话，直接退出；
        def idsFile = small.symbolFile
        if (!idsFile.exists()) return

        // 收集所有Vendor.aar
        // Check if has any vendor aars
        def firstLevelVendorAars = [] as Set<ResolvedDependency>
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
                Set<ResolvedDependency> reservedAars = new HashSet<>()
                firstLevelVendorAars.each {
                    Log.warn("Using vendor aar '$it.name'")

                    // If we don't split the aar then we should reserved all it's transitive aars.
                    collectTransitiveAars(it, reservedAars)
                }
                reservedAars.each {
                    mUserLibAars.add(group: it.moduleGroup, name: it.moduleName, version: it.moduleVersion)
                }
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
        // Map to `lib.**` resources id first, and then the host one.
        def libEntries = [:]
        File hostSymbol = new File(rootSmall.preIdsDir, "${rootSmall.hostModuleName}-R.txt")
        if (hostSymbol.exists()) {
            libEntries += SymbolParser.getResourceEntries(hostSymbol)
        }

        // compile project
        mTransitiveDependentLibProjects.each {
            File libSymbol = new File(it.projectDir, 'public.txt')
            libEntries += SymbolParser.getResourceEntries(libSymbol)
        }

        // 添加ProvidedCompile aar库的R.txt
        // todo: 为啥此处缓存的lib.style-R.txt，不是lib.style库的public.txt(等同symbols/R.txt)
        if (isProvidedAar()) {
            File aarLibSymbol = new File(rootSmall.preIdsDir, "lib.style-R.txt")
            libEntries += SymbolParser.getResourceEntries(aarLibSymbol)
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

            // 剩余的资源，就是插件自身的资源：添加到retainedEntries保留列表中
            be.isStyleable ? retainedStyleables.add(be) : retainedEntries.add(be)
//            Log.action("prepareSplit", " retainedEntries.add($be), from($k)")
        }

        // 保留所有已经被删除的public.txt资源: 前面已经过滤过，剩余的就是已经被bundle删除掉的.
        // Q: 这种也需要保留么？
        // TODO: retain deleted public entries
        if (publicEntries.size() > 0) {
            throw new RuntimeException("No support deleting resources on lib.* now!\n" +
                    "  - ${publicEntries.keySet().join(", ")}\n" +
                    "see https://github.com/wequick/Small/issues/53 for more information.")

//            publicEntries.each { k, e ->
//                e._typeId = e.typeId
//                e._entryId = e.entryId
//                e.entryId = Aapt.ID_DELETED
//
//                def re = retainedPublicEntries.find{it.type == e.type}
//                e.typeId = (re != null) ? re.typeId : Aapt.ID_DELETED
//            }
//            publicEntries.each { k, e ->
//                retainedPublicEntries.add(e)
//            }
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
//                Log.action("prepareSplit", " retainedTypes.add currType($currType) from retainedEntrie($e)")
            }
            // 记录所有IdMaps: 后续，修改XML资源ID时，需要用到
            def newResId = pid | (e._typeId << 16) | e._entryId
            def newResIdStr = "0x${Integer.toHexString(newResId)}"
            staticIdMaps.put(e.id, newResId)    // 前面已经处理过libEntries(肯定是public固定的), 这里处理剩余的工程自身的ID，因此需要在分配资源后再处理;
            staticIdStrMaps.put(e.idStr, newResIdStr)

            // Q: 不懂
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
        def vendorEntries = new HashMap<String, HashSet<SymbolParser.Entry>>()
        def vendorStyleableKeys = new HashMap<String, HashSet<String>>()
        transitiveVendorAars.each { aar ->
            String path = aar.path
            File aarOutput = small.buildCaches.get(path)
            if (aarOutput == null) {
                return
            }
            String resPath = new File(aarOutput, 'res').absolutePath
            File symbol = new File(aarOutput, 'R.txt')
            Set<SymbolParser.Entry> resTypeEntries = new HashSet<>()
            Set<String> resStyleableKeys = new HashSet<>()

            // Collect the resource entries declared in the aar res directory
            // This is all the arr's own resource: `R.layout.*', `R.string.*' and etc.
            collectReservedResourceKeys(aar.version, resPath, resTypeEntries, resStyleableKeys)

            // Collect the id entries for the aar, fix #230
            // This is all the aar id references: `R.id.*'
            def idEntries = []
            def libIdKeys = []
            libEntries.each { k, v ->
                if (v.type == 'id') {
                    libIdKeys.add(v.key)
                }
            }
            SymbolParser.collectResourceKeys(symbol, 'id', libIdKeys, idEntries, null)
            resTypeEntries.addAll(idEntries)

            // Collect the resource references from *.class
            // This is all the aar coding-referent fields: `R.*.*'
            // We had to parse this cause the aar maybe referenced to the other external aars like
            // `AppCompat' and so on, so that we should keep those external `R.*.*' for current aar.
            // Fix issue #271.
            File jar = new File(aarOutput, 'jars/classes.jar')
            if (jar.exists()) {
                def codedTypeEntries = []
                def codedStyleableKeys = []
                File interDir = new File(project.buildDir, "intermediates")
                File aarSymbolsDir = new File(interDir, 'small-symbols')
                File refDir = new File(aarSymbolsDir, path)
                File refFile = new File(refDir, 'R.txt')
                if (refFile.exists()) {
                    // Parse from file
                    SymbolParser.collectAarResourceKeys(refFile, codedTypeEntries, codedStyleableKeys)
                } else {
                    // Parse classes
                    if (!refDir.exists()) refDir.mkdirs()

                    File unzipDir = new File(refDir, 'classes')
                    project.copy {
                        from project.zipTree(jar)
                        into unzipDir
                    }
                    Set<Map> resRefs = []
                    unzipDir.eachFileRecurse(FileType.FILES, {
                        if (!it.name.endsWith('.class')) return

                        ClassFileUtils.collectResourceReferences(it, resRefs)
                    })

                    // TODO: read the aar package name once and store
                    File manifestFile = new File(aarOutput, 'AndroidManifest.xml')
                    def manifest = new XmlParser().parse(manifestFile)
                    String aarPkg = manifest.@package.replaceAll('\\.', '/')

                    def pw = new PrintWriter(new FileWriter(refFile))
                    resRefs.each {
                        if (it.pkg != aarPkg) {
                            println "Unresolved refs: $it.pkg/$it.type/$it.name for $aarPkg"
                            return
                        }

                        def type = it.type
                        def name = it.name
                        def key = "$type/$name"
                        if (type == 'styleable') {
                            codedStyleableKeys.add(type)
                        } else {
                            codedTypeEntries.add(new SymbolParser.Entry(type, name))
                        }
                        pw.println key
                    }
                    pw.flush()
                    pw.close()
                }

                resTypeEntries.addAll(codedTypeEntries)
                resStyleableKeys.addAll(codedStyleableKeys)
            }

            vendorEntries.put(path, resTypeEntries)
            vendorStyleableKeys.put(path, resStyleableKeys)
        }

        def vendorTypes = new HashMap<String, List<Map>>()
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
                "vendorTypes($vendorTypes)"   //idMaps($staticIdStrMaps), 特别多值
    }

    protected int getABIFlag() {
        def abis = []

        def jniDirs = android.sourceSets.main.jniLibs.srcDirs
        if (jniDirs == null) jniDirs = []

        // Collect ABIs from AARs
        def mergeJniLibsTask = project.tasks.withType(TransformTask.class).find {
            it.variantName == 'release' && it.transform.name == 'mergeJniLibs'
        }
        if (mergeJniLibsTask != null) {
            jniDirs.addAll(mergeJniLibsTask.streamInputs.findAll {
                it.isDirectory() && !shouldStripInput(it)
            })
        }

        // Filter ABIs
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

    // 是否过滤input文件
    protected boolean shouldStripInput(File input) {
        AarPath aarPath = new AarPath(project, input)
//        Log.action("shouldStripInput", "[$project.name] check input($aarPath.inputFile)")
        for (aar in small.splitAars) {
            if (aarPath.explodedFromAar(aar)) {
//                Log.action("shouldStripInput", "[$project.name] Strip input($aarPath.inputFile) for aar($aar)")
                return true
            }
        }
//        Log.result "[shouldStripInput] aarPath: " + aarPath.getInputFile()
        return false
    }

    protected void hookVariantTask(BaseVariant variant) {
        hookMergeAssets(variant.mergeAssets)

//        hookRes(variant.mergeResources)

        hookProcessManifest(small.processManifest)

        hookAapt(small.aapt)

        hookJavac(small.javac, variant.buildType.minifyEnabled)

        hookKotlinCompile()

        def transformTasks = project.tasks.withType(TransformTask.class)
        def mergeJniLibsTask = transformTasks.find {
            it.transform.name == 'mergeJniLibs' && it.variantName == variant.name
        }
        hookMergeJniLibs(mergeJniLibsTask)

        def mergeJavaResTask = transformTasks.find {
            it.transform.name == 'mergeJavaRes' && it.variantName == variant.name
        }
        hookMergeJavaRes(mergeJavaResTask)

        // Hook clean task to unset package id
        project.clean.doLast {
            sPackageIds.remove(project.name)
        }
    }

    /**
     * Hook merge-jniLibs task to ignores the lib.* native libraries
     * TODO: filter the native libraries while exploding aar
     */
    def hookMergeJniLibs(TransformTask t) {
        stripAarFiles(t, { splitPaths ->
            t.streamInputs.each {
                if (shouldStripInput(it)) {
                    splitPaths.add(it)
                }
            }
        })
    }

    /**
     * Hook merge-javaRes task to ignores the lib.* jar assets
     */
    def hookMergeJavaRes(TransformTask t) {
        stripAarFiles(t, { splitPaths ->
            t.streamInputs.each {
                if (shouldStripInput(it)) {
                    splitPaths.add(it)
                }
            }
        })
    }

    /**
     * Hook merge-assets task to ignores the lib.* assets
     * TODO: filter the assets while exploding aar
     */
    private void hookMergeAssets(MergeSourceSetFolders t) {
        stripAarFiles(t, { paths ->
//            Log.header "[hookMergeAssets] inputDirectorySets($t.inputDirectorySets), outputDir($t.outputDir)"
            t.inputDirectorySets.each {
                // configName: 对于aar，就是版本号(23.2.1)；对于Module，一般都是(main, release)
                if (it.configName == 'main' || it.configName == 'release') return

                it.sourceFiles.each {
//                    Log.header "[hookMergeAssets] Check AssetsSourceFiles($it)"
                    if (shouldStripInput(it)) {
                        paths.add(it)
//                        Log.header "[hookMergeAssets] fliterAssetsSourceFiles($it)"
                    }
                }
            }
        })
    }

    /**
     * A hack way to strip aar files:
     *  - Strip the task inputs before the task execute
     *  - Restore the inputs after the task executed
     * by what the task doesn't know what happen, and will be considered as 'UP-TO-DATE'
     * at next time it be called. This means a less I/O.
     * @param t the task who will merge aar files
     * @param closure the function to gather all the paths to be stripped
     */
    private static void stripAarFiles(Task t, Closure closure) {
        t.doFirst {
            List<File> stripPaths = []
            closure(stripPaths)

            Set<Map> strips = []
            stripPaths.each {
                def backup = new File(it.parentFile, "$it.name~")
                strips.add(org: it, backup: backup)
                it.renameTo(backup)
            }
            // 通过扩展的方式，添加成员变量，以便doLast时，能够恢复;
            it.extensions.add('strips', strips)

            Log.header "[stripAarFiles] HookTask.doFirst($t.name), stripPaths($stripPaths)"
        }
        // 以便doLast时，恢复ProvidedCompile的aar;
        t.doLast {
            Set<Map> strips = (Set<Map>) it.extensions.getByName('strips')
            strips.each {
                it.backup.renameTo(it.org)
            }
        }
    }

    protected static void collectAars(File d, Project src, Set outAars) {
        if (!d.exists()) return

        d.eachLine { line ->
            def module = line.split(':')
            def N = module.size()
            def aar = [group: module[0], name: module[1], version: (N == 3) ? module[2] : '']
            if (!outAars.contains(aar)) {
                outAars.add(aar)
            }
        }
    }

    protected void collectLibProjects(Project project, Set<Project> outLibProjects) {
        DependencySet compilesDependencies = project.configurations.compile.dependencies
        Set<DefaultProjectDependency> allLibs = compilesDependencies.withType(DefaultProjectDependency.class)
        allLibs.each {
            def dependency = it.dependencyProject
            if (rootSmall.isLibProject(dependency)) {
                outLibProjects.add(dependency)
                collectLibProjects(dependency, outLibProjects)
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

        // Collect transitive dependent `lib.*' projects
        mTransitiveDependentLibProjects = new HashSet<>()
        mTransitiveDependentLibProjects.addAll(mProvidedProjects)
        mProvidedProjects.each {
            collectLibProjects(it, mTransitiveDependentLibProjects)
        }

        // Collect aar(s) in lib.*
        mTransitiveDependentLibProjects.each { lib ->
            // lib.* dependencies
            collectAarsOfProject(lib, true, smallLibAars)
        }

        // Collect aar(s) in host
        collectAarsOfProject(rootSmall.hostProject, false, smallLibAars)

        // 添加工程Provided aar到smallLibAars
        // smallLibAars += aars;
        if (isProvidedAar()) {
            smallLibAars.add(group: "com.example.mysmall.lib.style", name: "libstyle", version: "0.0.1-SNAPSHOT")
        }

        small.splitAars = smallLibAars
        small.retainedAars = mUserLibAars
    }

    // todo: debug
    protected static def collectAarsOfLibrary(Project lib, HashSet outAars) {
        // lib.* self
        outAars.add(group: lib.group, name: lib.name, version: lib.version)
        Log.action("collectAarsOfLibrary", " add($lib.group,$lib.name, $lib.version")

        // lib.* self for android plugin 2.3.0+
        File dir = lib.projectDir
        outAars.add(group: dir.parentFile.name, name: dir.name, version: lib.version)
//        Log.action("collectAarsOfLibrary", " add($dir.parentFile.group,$dir.name, $lib.version")

        Log.action("collectAarsOfLibrary", " add $lib.name to outAars($outAars)")
    }

    protected def collectAarsOfProject(Project project, boolean isLib, HashSet outAars) {
        String dependenciesFileName = "$project.name-D.txt"

        // Pure aars
        File file = new File(rootSmall.preLinkAarDir, dependenciesFileName)
        collectAars(file, project, outAars) // project无效，可去除

        // Jar-only aars
        file = new File(rootSmall.preLinkJarDir, dependenciesFileName)
        collectAars(file, project, outAars) // project无效，可去除

        // collet libProject self aars
        if (isLib) {
            collectAarsOfLibrary(project, outAars)
        }

        Log.action("collectAarsOfLibrary", " add $project.name to outAars($outAars), isLib($isLib)")
    }

    private def hookProcessManifest(Task processManifest) {
        // 去除所有ProvidedCompile的任务
        // If an app.A dependent by lib.B and both of them declare application@name in their
        // manifests, the `processManifest` task will raise an conflict error.
        // Cause the release mode doesn't need to merge the manifest of lib.*, simply split
        // out the manifest dependencies from them.
        if (processManifest.hasProperty('providers')) {
            processManifest.providers = []
            Log.header "[${project.name}] hookProcessManifest($processManifest.name) hasProperty('providers')! so, don't filter lib.xxx "
        } else {
            processManifest.doFirst { MergeManifests it ->
                Log.header "[${project.name}] hookProcessManifest($processManifest.name) MergeManifestsTask($it.name) Task.project($it.project.name), pluginType($pluginType)"
                if (pluginType != PluginType.App) return

                // 遍历每一个Task的compile依赖，排除lib库
                def libs = it.libraries
                def smallLibs = []
                libs.each {
                    def components = it.name.split(':') // e.g. 'Sample:lib.style:unspecified'
                    if (components.size() != 3) return

                    // \build\intermediates\exploded-aar\
                    // Q: 为啥没有com.android.support/下的Manifest文件呢？
                    Log.success "[${project.name}] check MergeManifestsTask.libraries($it.name)"
                    // 排除所有lib库的Manifest文件
                    def projectName = components[1]
                    if (!rootSmall.isLibProject(projectName)) return

                    smallLibs.add(it)

                    Log.success "[${project.name}] split library Manifest files... $it.name, file($it.manifest.absolutePath)"
                }
                libs.removeAll(smallLibs)
                it.libraries = libs
            }
        }

        // 解决Manifest合并时的错误！
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

            Log.footer "[${project.name}] from ($it.manifestInputs) gen manifestOutputFile($manifestFile)"

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
                        // Remove all the unused keys, fix #313
                        filterKeys.each {
                            line = line.replaceAll(" $it=\"[^\"]+\"", "")
                        }
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

            // 收集所有公共依赖的[pkgId:pkgName]: 后续通过pkgId，找到pkgName，用于更新资源ID
            // Collect the DynamicRefTable [pkgId => pkgName]
            def libRefTable = [:]
            mTransitiveDependentLibProjects.each {
                def libAapt = it.tasks.withType(ProcessAndroidResources.class).find {
                    it.variantName.startsWith('release')
                }
                def pkgName = libAapt.packageForR
                def pkgId = sPackageIds[it.name]
                libRefTable.put(pkgId, pkgName)
            }

            // support Provided aar：
            //  isProvidedAar(): 很奇怪，此处调用这个方法，就会报错"Could not find method isProvidedAar() for arguments [] on task ':lib.style:processReleaseResources'"
            if (getPluginType().equals(PluginType.App)) {
//                def pkgId = sPackageIds["com.example.mysmall.lib.style"]
                libRefTable.put(new Integer(0x79), "com.example.mysmall.lib.style")
            }

            Aapt aapt = new Aapt(unzipApDir, rJavaFile, symbolFile, rev)
            Log.success "[${project.name}] ReAapt symbolFile($symbolFile), rJavaFile($rJavaFile) unzipApDir($unzipApDir)"

            if (small.retainedTypes != null && small.retainedTypes.size() > 0) {
                // 这两段难理解; 只能反推; 屏蔽调后，看看打包结果如何，对比就能看出来作用！
                // 过滤res/目录：排除掉filteredResources资源
                aapt.filterResources(small.retainedTypes, filteredResources)
                Log.success "[${project.name}] split library res files..."

                // 修改资源ID：处理resources.arsc文件、XML文件、R.txt
                aapt.filterPackage(small.retainedTypes, small.packageId, small.idMaps, libRefTable,
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
                    File aarOutput = small.buildCaches.get(name)
                    // TODO: read the aar package name once and store
                    File manifestFile = new File(aarOutput, 'AndroidManifest.xml')
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

                if (sourceOutputDir.deleteDir()) {
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
            def nullOutput = new ByteArrayOutputStream()
            if (System.properties['os.name'].toLowerCase().contains('windows')) {
                // Avoid the command becomes too long to execute on Windows.
                updatedResources.each { res ->
                    project.exec {
                        executable aaptExe
                        workingDir unzipApDir
                        args 'add', apFile.path, res

                        standardOutput = nullOutput
                    }
                }
            } else {
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
    }

    protected def addClasspath(Task javac) {
        javac.doFirst {
            // Dynamically provided jars
            javac.classpath += project.files(getLibraryJars().findAll{ it.exists() })
        }
    }

    private def hookKotlinCompile() {
        project.tasks.all {
            if (it.name.startsWith('compile')
                    && it.name.endsWith('Kotlin')
                    && it.hasProperty('classpath')) {
                addClasspath(it)
            }
        }
    }

    /**
     * Hook javac task to split libraries' R.class <br/>
     * 在javac后重新编译App的R.class文件，以便后续编译App源码时 能应用修改了的资源ID;
     * 由于lib库工程(以及aar)没有静态内联优化，因此无需重新编译其他class文件！
     */
    private def hookJavac(Task javac, boolean minifyEnabled) {
        addClasspath(javac)
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
        Set<SymbolParser.Entry> outTypeEntries = new HashSet<>()
        Set<String> outStyleableKeys = new HashSet<>()
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

    protected void collectReservedResourceKeys(config, path,
                                               Set<SymbolParser.Entry> outTypeEntries,
                                               Set<String> outStyleableKeys) {
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
                    String type = it.@type
                    if (type != null) { // <file name="activity_main" ... type="layout"/>
                        def name = it.@name
                        if (type == 'mipmap'
                                && (name == 'ic_launcher' || name == 'ic_launcher_round')) {
                            // NO NEED IN BUNDLE
                            return
                        }
                        def key = new SymbolParser.Entry(type, name) // layout/activity_main
                        outTypeEntries.add(key)
                        return
                    }

                    // 如果没有type字段：继续遍历，children.name 来判断类型
                    it.children().each {
                        type = it.name()
                        String name = it.@name
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
                                    def key = new SymbolParser.Entry('attr', attr)
                                    outTypeEntries.add(key)
                                }
                                String key = "${name}_${attr}"
                                outStyleableKeys.add(key)
                            }
                            outStyleableKeys.add(name)
                            return
                        } else if (type.endsWith('-array')) {
                            // string-array or integer-array
                            type = 'array'
                        }

                        def key = new SymbolParser.Entry(type, name)
                        outTypeEntries.add(key)
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

        // 缓存全局的PackageIds：和工程名称绑定
        sPackageIds.put(project.name, pp)
    }

    private boolean isProvidedAar() {
        getPluginType().equals(PluginType.App)
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
        if (sPackageIdBlackList.contains(pp)) {
            pp = (pp + maxPP) >> 1
        }
        return pp
    }

    private static sPackageIdBlackList = [
            0x03 // HTC
            ,0x10 // Xiao Mi
    ] as ArrayList<Integer>
}
