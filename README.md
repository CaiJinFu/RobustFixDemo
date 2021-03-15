# RobustFixDemo

## JavaSsist介绍

定义:javassist也称为动态编译，动态编译技术通过操作Java字节码的方式在JVM中生成class字节码中动态添加元素 或 修改代码，发生在Class字节码生成后(打包成dex之前，编译时之后)，也称为Class字节码手术刀。

## 动态编程解决什么问题?

动态修改编译后的Class字节码 实现了对APP实现了修改的无限可能。

1. 组件化
2. 热修复
3. 增量升级
4. AndroidStudio插件



## 千万级应用美团Robust修复原理



Robust 的原理可以简单描述为：

1、打基础包时插桩，在每个方法前插入一段类型为 ChangeQuickRedirect 静态变量的逻辑，插入过程对业务开发是完全透明

2、加载补丁时，从补丁包中读取要替换的类及具体替换的方法实现，新建ClassLoader加载补丁dex。当changeQuickRedirect不为null时，可能会执行到accessDispatch从而替换掉之前老的逻辑，达到fix的目的

![img](https:////upload-images.jianshu.io/upload_images/5125122-fc799bbd4a587560.png?imageMogr2/auto-orient/strip|imageView2/2/w/1200/format/webp)

Robust 官方介绍示例图

下面通过Robust的源码来进行分析。
 首先看一下打基础包是插入的代码逻辑，如下：

```tsx
public static ChangeQuickRedirect u;
protected void onCreate(Bundle bundle) {
        //为每个方法自动插入修复逻辑代码，如果ChangeQuickRedirect为空则不执行
        if (u != null) {
            if (PatchProxy.isSupport(new Object[]{bundle}, this, u, false, 78)) {
                PatchProxy.accessDispatchVoid(new Object[]{bundle}, this, u, false, 78);
                return;
            }
        }
        super.onCreate(bundle);
        ...
    }
```

Robust的核心修复源码如下：

```java
public class PatchExecutor extends Thread {
    @Override
    public void run() {
        ...
        applyPatchList(patches);
        ...
    }
    /**
     * 应用补丁列表
     */
    protected void applyPatchList(List<Patch> patches) {
        ...
        for (Patch p : patches) {
            ...
            currentPatchResult = patch(context, p);
            ...
            }
    }
     /**
     * 核心修复源码
     */
    protected boolean patch(Context context, Patch patch) {
        ...
        //新建ClassLoader
        DexClassLoader classLoader = new DexClassLoader(patch.getTempPath(), context.getCacheDir().getAbsolutePath(),
                null, PatchExecutor.class.getClassLoader());
        patch.delete(patch.getTempPath());
        ...
        try {
            patchsInfoClass = classLoader.loadClass(patch.getPatchesInfoImplClassFullName());
            patchesInfo = (PatchesInfo) patchsInfoClass.newInstance();
            } catch (Throwable t) {
             ...
        }
        ...
        //通过遍历其中的类信息进而反射修改其中 ChangeQuickRedirect 对象的值
        for (PatchedClassInfo patchedClassInfo : patchedClasses) {
            ...
            try {
                oldClass = classLoader.loadClass(patchedClassName.trim());
                Field[] fields = oldClass.getDeclaredFields();
                for (Field field : fields) {
                    if (TextUtils.equals(field.getType().getCanonicalName(), ChangeQuickRedirect.class.getCanonicalName()) && TextUtils.equals(field.getDeclaringClass().getCanonicalName(), oldClass.getCanonicalName())) {
                        changeQuickRedirectField = field;
                        break;
                    }
                }
                ...
                try {
                    patchClass = classLoader.loadClass(patchClassName);
                    Object patchObject = patchClass.newInstance();
                    changeQuickRedirectField.setAccessible(true);
                    changeQuickRedirectField.set(null, patchObject);
                    } catch (Throwable t) {
                    ...
                }
            } catch (Throwable t) {
                 ...
            }
        }
        return true;
    }
}
```



### 优点

- 高兼容性（Robust只是在正常的使用DexClassLoader）、高稳定性，修复成功率高达99.9%
- 补丁实时生效，不需要重新启动
- 支持方法级别的修复，包括静态方法
- 支持增加方法和类
- 支持ProGuard的混淆、内联、优化等操作

### 缺点

- 代码是侵入式的，会在原有的类中加入相关代码
- so和资源的替换暂时不支持
- 会增大apk的体积，平均一个函数会比原来增加17.47个字节，10万个函数会增加1.67M



参考：[https://tech.meituan.com/2016/09/14/android-robust.html](https://tech.meituan.com/2016/09/14/android-robust.html)

## 手写字节码插件技术

### 1.1 创建Gradle Module 

AndroidStudio中是没有新建类似Gradle Plugin这样的选项的，那我们如何在AndroidStudio中编写Gradle插件，并打包出来呢？

> (1) 首先，你得新建一个Android Project
> (2) 然后再新建一个Module，这个Module用于开发Gradle插件，同样，Module里面没有gradle plugin给你选，但是我们只是需要一个“容器”来容纳我们写的插件，因此，你可以随便选择一个Module类型（如Phone&Tablet Module或Android Librarty）,因为接下来一步我们是将里面的大部分内容删除，所以选择哪个类型的Module不重要。
> (3) 将Module里面的内容删除，只保留build.gradle文件和src/main目录。
> 由于gradle是基于groovy，因此，我们开发的gradle插件相当于一个groovy项目。所以需要在main目录下新建groovy目录
> (4) groovy又是基于Java，因此，接下来创建groovy的过程跟创建java很类似。在groovy新建包名，如：com.hc.plugin，然后在该包下新建groovy文件，通过new->file->MyPlugin.groovy来新建名为MyPlugin的groovy文件。
> (5) 为了让我们的groovy类申明为gradle的插件，新建的groovy需要实现org.gradle.api.Plugin接口。如下所示：





```groovy
package  com.hc.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project

public class MyPlugin implements Plugin<Project> {

    void apply(Project project) {
        System.out.println("========================");
        System.out.println("hello gradle plugin!");
        System.out.println("========================");
    }
}
```




> (6) 现在，我们已经定义好了自己的gradle插件类，接下来就是告诉gradle，哪一个是我们自定义的插件类，因此，需要在main目录下新建resources目录，然后在resources目录里面再新建META-INF目录，再在META-INF里面新建gradle-plugins目录。最后在gradle-plugins目录里面新建properties文件，注意这个文件的命名，你可以随意取名，但是后面使用这个插件的时候，会用到这个名字。
>
> 比如，你取名为com.hc.gradle.properties，而在其他build.gradle文件中使用自定义的插件时候则需写成：



> apply plugin: 'com.hc.gradle'


**然后在com.hc.gradle.properties文件里面指明你自定义的类**

> implementation-class=com.hc.plugin.MyPlugin


现在，你的目录应该如下：

![自定义插件目录结构](https://img-blog.csdn.net/20160702123302689)

> (7) 因为我们要用到groovy以及后面打包要用到maven,所以在我们自定义的Module下的build.gradle需要添加如下代码：

```groovy
apply plugin: 'groovy'
apply plugin: 'maven'

dependencies {
    //gradle sdk
    compile gradleApi()
    //groovy sdk
    compile localGroovy()
}

repositories {
    mavenCentral()
}
```

### 1.2 打包到本地Maven

		前面我们已经自定义好了插件，接下来就是要打包到Maven库里面去了，你可以选择打包到本地，或者是远程服务器中。在我们自定义Module目录下的build.gradle添加如下代码：

```groovy
//group和version在后面使用自定义插件的时候会用到
group='com.hc.plugin'
version='1.0.0'

uploadArchives {
    repositories {
        mavenDeployer {
            //提交到远程服务器：
           // repository(url: "http://www.xxx.com/repos") {
            //    authentication(userName: "admin", password: "admin")
           // }
           //本地的Maven地址设置为D:/repos
            repository(url: uri('D:/repos'))
        }
    }
}
```

		其中，group和version后面会用到，我们后面再讲。虽然我们已经定义好了打包地址以及打包相关配置，但是还需要我们让这个打包task执行。点击AndroidStudio右侧的gradle工具，如下图所示：

![上传Task](https://img-blog.csdn.net/20160702130539639)

		可以看到有uploadArchives这个Task,双击uploadArchives就会执行打包上传啦！执行完成后，去我们的Maven本地仓库查看一下：

![打包上传后](https://img-blog.csdn.net/20160702130836877)

		其中，com/hc/plugin这几层目录是由我们的group指定，myplugin是模块的名称，1.0.0是版本号（version指定）。



### 1.3 使用自定义的插件

		接下来就是使用自定义的插件了，一般就是在app这个模块中使用自定义插件，因此在app这个Module的build.gradle文件中，需要指定本地Maven地址、自定义插件的名称以及依赖包名。简而言之，就是在app这个Module的build.gradle文件中后面附加如下代码：

```groovy
buildscript {
    repositories {
        maven {//本地Maven仓库地址
            url uri('D:/repos')
        }
    }
    dependencies {
        //格式为-->group:module:version
        classpath 'com.hc.plugin:myplugin:1.0.0'
    }
}
//com.hc.gradle为resources/META-INF/gradle-plugins
//下的properties文件名称
apply plugin: 'com.hc.gradle'
```


好啦，接下来就是看看效果啦！先clean project(很重要！),然后再make project.从messages窗口打印如下信息：

![使用自定义插件](https://img-blog.csdn.net/20160702131957936)

好啦，现在终于运行了自定义的gradle插件啦！

### 1.4 开发只针对当前项目的Gradle插件

前面我们讲了如何自定义gradle插件并且打包出去，可能步骤比较多。有时候，你可能并不需要打包出去，只是在这一个项目中使用而已，那么你无需打包这个过程。

只是针对当前项目开发的Gradle插件相对较简单。步骤之前所提到的很类似，只是有几点需要注意：

> 1. 新建的Module名称必须为BuildSrc
> 2. 无需resources目录

目录结构如下所示：

![针对当前项目的gradle插件目录](https://img-blog.csdn.net/20160702135323958)

其中，build.gradle内容为：

```groovy
apply plugin: 'groovy'

dependencies {
    compile gradleApi()//gradle sdk
    compile localGroovy()//groovy sdk
}

repositories {
    jcenter()
}

```

SecondPlugin.groovy内容为：

```groovy
package  com.hc.second

import org.gradle.api.Plugin
import org.gradle.api.Project

public class SecondPlugin implements Plugin<Project> {

    void apply(Project project) {
        System.out.println("========================");
        System.out.println("这是第二个插件!");
        System.out.println("========================");
    }
}
 
```



在app这个Module中如何使用呢？直接在app的build.gradle下加入。

> apply plugin: com.hc.second.SecondPlugin


clean一下，再make project，messages窗口信息如下：

![打印信息](https://img-blog.csdn.net/20160702135750329)

由于之前我们自定义的插件我没有在app的build.gradle中删除，所以hello gradle plugin这条信息还在。
