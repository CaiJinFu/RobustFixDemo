package com.jackfruit.robust

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Project
import com.jackfruit.robust.ModifyTransform;
class ModifyPlugin implements Plugin<Project> {
  @Override
  void apply(Project project) {
    project.android.registerTransform(new ModifyTransform(project))
  }
}