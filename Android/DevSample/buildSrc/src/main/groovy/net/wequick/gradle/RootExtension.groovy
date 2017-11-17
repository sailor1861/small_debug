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

import net.wequick.gradle.util.Log
import org.gradle.api.Project
import org.gradle.util.VersionNumber

public class RootExtension extends BaseExtension {

    private static final String FD_BUILD_SMALL = 'build-small'
    private static final String FD_PRE_JAR = 'small-pre-jar'
    private static final String FD_PRE_AP = 'small-pre-ap'
    private static final String FD_PRE_IDS = 'small-pre-ids'
    private static final String FD_PRE_LINK = 'small-pre-link'
    private static final String FD_BASE = 'base'
    private static final String FD_LIBS = 'libs'
    private static final String FD_JAR = 'jar'
    private static final String FD_AAR = 'aar'

    /** The minimum small aar version required */
    private static final String REQUIRED_AAR_VERSION = '1.0.0'
    private static final VersionNumber REQUIRED_AAR_REVISION = VersionNumber.parse(REQUIRED_AAR_VERSION)

    /** The built version of gradle-small plugin */
    public static final String PLUGIN_VERSION = '1.2.0-alpha6'
    public static final VersionNumber PLUGIN_REVISION = VersionNumber.parse(PLUGIN_VERSION)

    /**
     * the public cache dir
     * default to #FD_BUILD_SMALL
     */
    String publicDir = FD_BUILD_SMALL;

    /**
     * the output dir of plugin Dest File(*.so)
     */
    String destOutputDir

    /**
     * Version of aar net.wequick.small:small
     * default to `gradle-small' plugin version 
     */
    String aarVersion

    /**
     * Host module name
     * default to `app'
     */
    String hostModuleName

    /** The parsed revision of `aarVersion' */
    private VersionNumber aarRevision

    /**
     * Strict mode, <tt>true</tt> if keep only resources in bundle's res directory.
     */
    boolean strictSplitResources = true

    /**
     * The default android configurations
     * - compileSdkVersion
     * - buildToolsVersion
     * - support library version (AppCompat and etc.)
     */
    protected AndroidConfig android

    /**
     * The default kotlin configurations
     * - version, the kotlin tools version
     */
    protected KotlinConfig kotlin

    // Small 自身功能
    /**
     * If <tt>true</tt> build plugins to host assets as *.apk,
     * otherwise build to host smallLibs as *.so
     */
    boolean buildToAssets = false

    // 仅用于打印消息！
    /** Count of libraries */
    protected int libCount

    /** Count of bundles */
    protected int bundleCount

    // 其他各子project， 需要依赖SmallSdk，就是通过compile smallProject
    // todo: 去除，改为aar引用
    /** Project of Small AAR module */
    protected Project smallProject

    // 很多处在使用:
    /** Project of host */
    protected Project hostProject

    // 很多处在使用: 重点突破项目
    /** Project of host which are automatically dependent by other bundle modules */
    protected Set<Project> hostStubProjects

    // 用于判断isLibProject(): 保留，用于支持compile(':project')模式
    /** Project of lib.* */
    protected Set<Project> libProjects

    // 无意义，可去除
    /** Project of app.* */
    protected Set<Project> appProjects

    /** Directory to output bundles (*.so) */
    protected File outputBundleDir

    private File preBuildDir

    /** Directory of pre-build host and android support jars */
    private File preBaseJarDir

    /** Directory of pre-build libs jars */
    private File preLibsJarDir

    /** Directory of pre-build resources.ap_ */
    private File preApDir

    /** Directory of pre-build R.txt */
    private File preIdsDir

    /** Directory of prepared dependencies */
    private File preLinkAarDir
    private File preLinkJarDir

    // 解析Gradle Task：用于区分buildLib、buildBundle: 可以移植到AndroidPlugin
    protected String mP // the executing gradle project name
    protected String mT // the executing gradle task name

    RootExtension(Project project) {
        super(project)

        hostModuleName = 'app'

        // todo: 为啥不能去除呢？ -- GradleSync, 就报错I.O异常
        // Create pre Dirs: 可以move到AndroidPlugin
        prepareBuildEnv(project)

        // Parse gradle task: 可以move到AndroidPlugin
        def sp = project.gradle.startParameter
        def t = sp.taskNames[0]
        if (t != null) {
            def p = sp.projectDir
            def pn = null
            if (p == null) {
                if (t.startsWith(':')) {
                    // gradlew :app.main:assembleRelease
                    def tArr = t.split(':')
                    if (tArr.length == 3) { // ['', 'app.main', 'assembleRelease']
                        pn = tArr[1]
                        t = tArr[2]
                    }
                }
            } else if (p != project.rootProject.projectDir) {
                // gradlew -p [project.name] assembleRelease
                pn = p.name
            }
            mP = pn
            mT = t
        }

        Log.header "project($project) mP($mP), mT($mT)"
    }

    /**
     * prepare Build Environment
     * 获取扩展属性，因此需要在Project.afterEvaluate后执行，才可以！
     * @param project
     */
    public void prepareBuildEnv(Project project) {
//        preBuildDir = new File(project.projectDir, FD_BUILD_SMALL)
        preBuildDir = new File(project.projectDir, getPublicDir())
        def interDir = new File(preBuildDir, FD_INTERMEDIATES)
        def jarDir = new File(interDir, FD_PRE_JAR)
        preBaseJarDir = new File(jarDir, FD_BASE)
        preLibsJarDir = new File(jarDir, FD_LIBS)
        preApDir = new File(interDir, FD_PRE_AP)
        preIdsDir = new File(interDir, FD_PRE_IDS)
        def preLinkDir = new File(interDir, FD_PRE_LINK)
        preLinkJarDir = new File(preLinkDir, FD_JAR)
        preLinkAarDir = new File(preLinkDir, FD_AAR)

    }


    public String getPublicDir() {
        Log.result "getPublicDir $publicDir"
        return publicDir
    }

    public File getOutBundleDir() {
        return outBundleDir;
    }

    public File getPreBuildDir() {
        return preBuildDir
    }

    public File getPreBaseJarDir() {
        return preBaseJarDir
    }

    public File getPreLibsJarDir() {
        return preLibsJarDir
    }

    public File getPreApDir() {
        return preApDir
    }

    public File getPreIdsDir() {
        return preIdsDir
    }

    public File getPreLinkJarDir() {
        return preLinkJarDir
    }

    public File getPreLinkAarDir() {
        return preLinkAarDir
    }

    public String getAarVersion() {
        if (aarVersion == null) {
            // Try to use the version of gradle-small plugin
            if (PLUGIN_REVISION < VersionNumber.parse('1.1.0-alpha2')) {
                throw new RuntimeException(
                        'Please specify Small aar version in your root build.gradle:\n' +
                                "small {\n    aarVersion = '[the_version]'\n}")
            }

            return PLUGIN_VERSION
        }

        if (aarRevision == null) {
            synchronized (this.class) {
                if (aarRevision == null) {
                    aarRevision = VersionNumber.parse(aarVersion)
                }
            }
        }
        if (aarRevision < REQUIRED_AAR_REVISION) {
            throw new RuntimeException(
                    "Small aar version $REQUIRED_AAR_VERSION is required. Current version is $aarVersion"
            )
        }

        return aarVersion
    }

    /** todo: 没有看到写这个Map的地方？ */
    Map<String, Set<String>> bundleModules = [:]

    public void bundles(String type, String name) {
        def modules = bundleModules.get(type)
        if (modules == null) {
            modules = new HashSet<String>()
            bundleModules.put(type, modules)
        }
        modules.add(name)
    }

    public void bundles(String type, names) {
        def modules = bundleModules.get(type)
        if (modules == null) {
            modules = new HashSet<String>()
            bundleModules.put(type, modules)
        }
        modules.addAll(names)
    }

    public File getBundleOutput(String bundleId) {
        def outputDir = outputBundleDir
        if (buildToAssets) {
            return new File(outputDir, "${bundleId}.apk")
        } else {
            def arch = System.properties['bundle.arch'] // Get from command line (-Dbundle.arch=xx)
            if (arch == null) {
                // Read from local.properties (bundle.arch=xx)
                def prop = new Properties()
                def file = project.rootProject.file('local.properties')
                if (file.exists()) {
                    prop.load(file.newDataInputStream())
                    arch = prop.getProperty('bundle.arch')
                }
                if (arch == null) arch = 'armeabi' // Default
            }
            def so = "lib${bundleId.replaceAll('\\.', '_')}.so"
            return new File(outputDir, "$arch/$so")
        }
    }

    /** Check if is building any libs (lib.*) */
    protected boolean isBuildingLibs() {
        if (mT == null) return false // no tasks

        if (mP == null) {
            // ./gradlew buildLib
            return (mT == 'buildLib')
        } else {
            // ./gradlew -p lib.xx aR | ./gradlew :lib.xx:aR
            return (mP.startsWith('lib.') && (mT == 'assembleRelease' || mT == 'aR'))
        }
    }

    /** Check if is building any apps (app.*) */
    protected boolean isBuildingApps() {
        if (mT == null) return false // no tasks

        if (mP == null) {
            // ./gradlew buildBundle
            return (mT == 'buildBundle')
        } else {
            // ./gradlew -p app.xx aR | ./gradlew :app.xx:aR
            return (mP.startsWith('app.') && (mT == 'assembleRelease' || mT == 'aR'))
        }
    }

    protected boolean isLibProject(Project project) {
        boolean found = false;
        if (libProjects != null) {
            found = libProjects.contains(project);
        }
        // 可去除
        if (!found && hostStubProjects != null) {
            found = hostStubProjects.contains(project);
        }

        // only Debug lib.style
        Log.success "$project.name isLibProject:$found"
        if ("lib.style".equals(project.name)) {
            found = true;
            Log.result "[only Debug lib.style]${project.name} project Always is LibProject!"
        }

        return found;
    }

    protected boolean isLibProject(String name) {
        boolean found = false;
        if (libProjects != null) {
            found = libProjects.find{ it.name == name } != null;
        }
        // 可去除
        if (!found && hostStubProjects != null) {
            found = hostStubProjects.find{ it.name == name } != null;
        }

        // only Debug lib.style
        Log.result "[${project.name}] isLibProjectName($name):$found"
        if ("lib.style".equals(name)) {
            found = true;
            Log.success "[${project.name}] [only Debug lib.style] isLibProjectName($name), Always is LibProject!"
        }

        return found;
    }

    public def android(Closure closure) {
        android = new AndroidConfig()
        project.configure(android, closure)
    }

    class AndroidConfig {
        int compileSdkVersion
        String buildToolsVersion
        String supportVersion
    }

    public def kotlin(Closure closure) {
        kotlin = new KotlinConfig()
        project.configure(kotlin, closure)
    }

    class KotlinConfig {
        String version
    }
}
