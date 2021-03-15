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

  //    字节码池子
  def pool = ClassPool.default

  //    把class字节码加载到内存
  def project

  ModifyTransform(Project project) {
    this.project = project
  }

  @Override
  String getName() {
    return "jackfruit"
  }

  @Override
  Set<QualifiedContent.ContentType> getInputTypes() {
    return TransformManager.CONTENT_CLASS
  }

  @Override
  Set<? super QualifiedContent.Scope> getScopes() {
    return TransformManager.SCOPE_FULL_PROJECT
  }

  @Override
  boolean isIncremental() {
    return false
  }

  @Override
  void transform(TransformInvocation transformInvocation)
      throws TransformException, InterruptedException, IOException {
    super.transform(transformInvocation)
    //        准备工作
    //   project java 工程  java--class路径  class字节码加载内存
    //
    project.android.bootClasspath.each {
      pool.appendClassPath(it.absolutePath)
    }

    //          加载内存
    //        遍历上一个transform   传递进来的 文件
    //         class     ----》  jar  for
    transformInvocation.inputs.each {
      //            遍历jar包所有的类
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