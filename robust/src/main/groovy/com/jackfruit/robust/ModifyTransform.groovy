package com.jackfruit.robust

import com.android.SdkConstants
import com.android.build.api.transform.*
import com.android.build.gradle.internal.pipeline.TransformManager
import javassist.ClassPool
import javassist.CtClass
import javassist.CtMethod
import org.apache.commons.io.FileUtils
import org.gradle.api.Project

class ModifyTransform extends Transform {

  /**
   * javassist的类池，使用ClassPool类可以跟踪和控制所操作的类,它的工作方式与 JVM类装载器非常相似
   */
  def pool = ClassPool.default

  /**
   * 把class字节码加载到内存
   */
  def project

  ModifyTransform(Project project) {
    this.project = project
  }

  /**
   * 用于指明本Transform的名字，这个 name 并不是最终的名字，在TransformManager 中会对名字再处理
   */
  @Override
  String getName() {
    return "jackfruit"
  }

  /**
   * 用于指明Transform的输入类型，可以作为输入过滤的手段
   *  TransformManager.CONTENT_CLASS：表示要处理编译后的字节码，可能是 jar 包也可能是目录
   *  TransformManager.CONTENT_RESOURCES：表示处理标准的 java 资源
   */
  @Override
  Set<QualifiedContent.ContentType> getInputTypes() {
    return TransformManager.CONTENT_CLASS
  }

  /**
   * 用于指明Transform的作用域
   * TransformManager.PROJECT：只处理当前项目
   * TransformManager.SUB_PROJECTS：只处理子项目
   * TransformManager.PROJECT_LOCAL_DEPS：只处理当前项目的本地依赖,例如jar, aar
   * TransformManager.EXTERNAL_LIBRARIES：只处理外部的依赖库
   * TransformManager.PROVIDED_ONLY：只处理本地或远程以provided形式引入的依赖库
   * TransformManager.TESTED_CODE：只处理测试代码
   */
  @Override
  Set<? super QualifiedContent.Scope> getScopes() {
    return TransformManager.SCOPE_FULL_PROJECT
  }

  /**
   * 用于指明是否是增量构建。
   */
  @Override
  boolean isIncremental() {
    return false
  }

  /**
   * 核心方法，用于自定义处理,在这个方法中我们可以拿到要处理的.class文件路径、jar包路径、输出文件路径等，
   * 拿到文件之后就可以对他们进行操作
   */
  @Override
  void transform(TransformInvocation transformInvocation)
      throws TransformException, InterruptedException, IOException {
    super.transform(transformInvocation)
    //利用Transform-api处理.class文件有个标准流程，拿到输入路径->取出要处理的文件->处理文件->移动文件到输出路径
    project.android.bootClasspath.each {
      pool.appendClassPath(it.absolutePath)
    }

    //          加载内存
    //        遍历上一个transform   传递进来的 文件
    //         class     ----》  jar
    transformInvocation.inputs.each {
      //            遍历jar包所有的类
      //            处理 1   不处理 2  不处理
      it.jarInputs.each {
        pool.insertClassPath(it.file.absolutePath)
        //                设定输出参数，编译 的输入与输出一一对应
        def dest = transformInvocation.outputProvider.getContentLocation(
            it.name, it.contentTypes, it.scopes, Format.JAR
        )

        //                copy文件到输出
        FileUtils.copyFile(it.file, dest)
      }
      // for(Bean it: beans)
      //            所有的类 自己写的类
      it.directoryInputs.each {
        def preFileName = it.file.absolutePath
        //                耗内存  优化 apk 加载 class --》dex
        pool.insertClassPath(preFileName)
        //                修改class的代码
        findTarget(it.file, preFileName)

        def dest = transformInvocation.outputProvider.getContentLocation(
            it.name, it.contentTypes, it.scopes, Format.DIRECTORY
        )
        println "copy directory: " + it.file.absolutePath
        println "dest directory: " + dest.absolutePath
        // 将input的目录复制到output指定目录
        FileUtils.copyDirectory(it.file, dest)
      }
    }
  }

  private void findTarget(File dir, String fileName) {
    //        递归寻找到 class 结尾的字节码文件    他们找出来    修改
    if (dir.isDirectory()) {
      dir.listFiles().each {
        findTarget(it, fileName)
      }
    } else {
      //            .class
      modify(dir, fileName)
    }
  }

  private void modify(File dir, String fileName) {
    def filePath = dir.absolutePath
    if (!filePath.endsWith(SdkConstants.DOT_CLASS)) {
      return
    }
    if (filePath.contains('R$') || filePath.contains('R.class')
        || filePath.contains("BuildConfig.class")) {
      return
    }
    //        获得全类名   key -----》字节码对象 ctClass  ----》修改
    //
    //     .com.jackfruit.robustfixdemo.MainActivity.class
    def className = filePath.replace(fileName, "")
        .replace("\\", ".")
        .replace("/", ".")
    println "========className======== " + className
    def name = className.replace(SdkConstants.DOT_CLASS, "")
        .substring(1)
    CtClass ctClass = pool.get(name)
    //
    if (name.contains("com.jackfruit.robustfixdemo")) {
      //            如果这个类    是在 com.jackfruit.robustfixdemo 需要加入这些代码  if判断
      def body = "if (com.jackfruit.robustfixdemo.PatchProxy.isSupport()) {}"
      addCode(ctClass, body, fileName)
    }
  }

  private void addCode(CtClass ctClass, String body, String fileName) {
    if (ctClass.getName().contains("PatchProxy")) {
      return;
    }
    CtMethod[] methods = ctClass.getDeclaredMethods()
    for (method in methods) {
      if (method.getName().contains("isSupport")) {
        continue
      }

      method.insertBefore(body)
    }
    ctClass.writeFile(fileName)
    ctClass.detach()
  }
}