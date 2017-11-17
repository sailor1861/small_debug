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

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.compile.JavaCompile

public class AppExtension extends BundleExtension {

    /** Task of java compiler */
    JavaCompile javac

    /** Task of merge manifest */
    Task processManifest

    /** Variant application id */
    String packageName

    /** Package path for java classes */
    String packagePath

    /** Directory of all compiled java classes */
    File classesDir

    /** Directory of split compiled java classes */
    File bkClassesDir

    /** Symbol file - R.txt */
    File symbolFile

    /** File of resources.ap_ */
    File apFile

    // Application工程普通compile的R.java
    /** File of R.java */
    File rJavaFile

    /** File of merger.xml */
    File mergerXml

    /** Public symbol file - public.txt */
    File publicSymbolFile

    // 需要过滤的公共组件aars: 包括host.stub + libs. 【最核心数据，新增支持aar模式】
    // 对应AppPlugin.smallLibAars
    /** Paths of aar to split */
    Set<Map> splitAars

    // 需要保留的aars
    // 对应AppPlugin.mUserLibAars
    /** Paths of aar to retain */
    Set<Map> retainedAars

    /**
     * File of split R.java <br/>
     * 分离的R.java: 只有App自身的R.java: 未合成公共R前的App自身的R.java; 用于打包到插件自身包内;
     */
    File splitRJavaFile

    LinkedHashMap<Integer, Integer> idMaps
    LinkedHashMap<String, String> idStrMaps

    /**
     * "List(Map())"
     */
    ArrayList retainedTypes
    ArrayList retainedStyleables
    Map<String, List> vendorTypes
    Map<String, List> vendorStyleables

    /** List of all resource types
     * Do this only for the modules who's code really use R.xx of lib.*
     */
    ArrayList allTypes

    /** List of all resource styleables */
    ArrayList allStyleables

    AppExtension(Project project) {
        super(project)

        publicSymbolFile = new File(project.projectDir, 'public.txt')
    }
}
