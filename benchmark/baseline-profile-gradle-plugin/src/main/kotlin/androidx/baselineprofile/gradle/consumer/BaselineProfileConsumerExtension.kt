/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.baselineprofile.gradle.consumer

import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory

/**
 * Allows specifying settings for the Baseline Profile Consumer Plugin.
 */
abstract class BaselineProfileConsumerExtension @Inject constructor(
    objectFactory: ObjectFactory
) : BaselineProfileVariantConfiguration {

    companion object {
        private const val EXTENSION_NAME = "baselineProfile"

        internal fun register(project: Project): BaselineProfileConsumerExtension {
            val ext = project
                .extensions
                .findByType(BaselineProfileConsumerExtension::class.java)
            if (ext != null) {
                return ext
            }
            return project
                .extensions
                .create(EXTENSION_NAME, BaselineProfileConsumerExtension::class.java)
        }
    }

    val variants: NamedDomainObjectContainer<BaselineProfileVariantConfigurationImpl> =
        objectFactory.domainObjectContainer(BaselineProfileVariantConfigurationImpl::class.java)

    // Shortcut to access the "main" variant.
    private val main: BaselineProfileVariantConfiguration = variants.create("main") {

        // These are the default global settings.
        it.mergeIntoMain = null
        it.baselineProfileOutputDir = "generated/baselineProfiles"
        it.enableR8BaselineProfileRewrite = false
        it.saveInSrc = true
        it.automaticGenerationDuringBuild = false
    }

    /**
     * Controls the global [BaselineProfileVariantConfiguration.enableR8BaselineProfileRewrite].
     * Note that this value is overridden by per variant configurations.
     */
    override var enableR8BaselineProfileRewrite: Boolean?
        get() = main.enableR8BaselineProfileRewrite
        set(value) {
            main.enableR8BaselineProfileRewrite = value
        }

    /**
     * Controls the global [BaselineProfileVariantConfiguration.saveInSrc].
     * Note that this value is overridden by per variant configurations.
     */
    override var saveInSrc: Boolean?
        get() = main.saveInSrc
        set(value) {
            main.saveInSrc = value
        }

    /**
     * Controls the global [BaselineProfileVariantConfiguration.automaticGenerationDuringBuild].
     * Note that this value is overridden by per variant configurations.
     */
    override var automaticGenerationDuringBuild: Boolean?
        get() = main.automaticGenerationDuringBuild
        set(value) {
            main.automaticGenerationDuringBuild = value
        }

    /**
     * Controls the global [BaselineProfileVariantConfiguration.baselineProfileOutputDir].
     * Note that this value is overridden by per variant configurations.
     */
    override var baselineProfileOutputDir: String?
        get() = main.baselineProfileOutputDir
        set(value) {
            main.baselineProfileOutputDir = value
        }

    /**
     * Controls the global [BaselineProfileVariantConfiguration.mergeIntoMain].
     * Note that this value is overridden by per variant configurations.
     */
    override var mergeIntoMain: Boolean?
        get() = main.mergeIntoMain
        set(value) {
            main.mergeIntoMain = value
        }

    /**
     * Applies the global [BaselineProfileVariantConfiguration.filter].
     * This function is just a shortcut for `baselineProfiles.variants.main.filters { }`
     */
    override fun filter(action: FilterRules.() -> (Unit)) = main.filter(action)

    /**
     * Applies the global [BaselineProfileVariantConfiguration.filter].
     * This function is just a shortcut for `baselineProfiles.variants.main.filters { }`
     */
    override fun filter(action: Action<FilterRules>) = main.filter(action)

    /**
     * Applies global dependencies for baseline profiles. This has the same effect of defining
     * a baseline profile dependency in the dependency block. For example:
     * ```
     * dependencies {
     *     baselineProfile(project(":baseline-profile"))
     * }
     * ```
     */
    override fun from(project: Project, variantName: String?) = main.from(project, variantName)

    fun variants(
        action: Action<NamedDomainObjectContainer<BaselineProfileVariantConfigurationImpl>>
    ) {
        action.execute(variants)
    }

    fun variants(
        action: NamedDomainObjectContainer<out BaselineProfileVariantConfigurationImpl>.() -> Unit
    ) {
        action.invoke(variants)
    }
}

abstract class BaselineProfileVariantConfigurationImpl(val name: String) :
    BaselineProfileVariantConfiguration {

    internal val filters = FilterRules()
    internal val dependencies = mutableListOf<Pair<Project, String?>>()

    /**
     * @inheritDoc
     */
    override fun filter(action: FilterRules.() -> (Unit)) = action.invoke(filters)

    /**
     * @inheritDoc
     */
    override fun filter(action: Action<FilterRules>) = action.execute(filters)

    /**
     * @inheritDoc
     */
    override fun from(project: Project, variantName: String?) {
        dependencies.add(Pair(project, variantName))
    }
}

/**
 * Defines the configuration properties that each variant of a consumer module offers. Note that
 * also [BaselineProfileConsumerExtension] is an implementation of this interface and it's simply
 * a proxy to the `main` variant.
 */
interface BaselineProfileVariantConfiguration {

    /**
     * Enables R8 to rewrite the incoming human readable baseline profile rules to account for
     * synthetics, so they are preserved after optimizations by R8.
     * TODO: This feature is experimental and currently not working properly.
     *  https://issuetracker.google.com/issue?id=271172067.
     */
    var enableR8BaselineProfileRewrite: Boolean?

    /**
     * Specifies whether generated baseline profiles should be stored in the src folder.
     * When this flag is set to true, the generated baseline profiles are stored in
     * `src/<variant>/generated/baselineProfiles`.
     */
    var saveInSrc: Boolean?

    /**
     * Specifies whether baseline profiles should be regenerated when building, for example, during
     * a full release build for distribution. When set to true a new profile is generated as part
     * of building the release build. This including rebuilding the non minified release, running
     * the baseline profile tests and ultimately building the release build.
     */
    var automaticGenerationDuringBuild: Boolean?

    /**
     * Specifies the output directory for generated baseline profiles when
     * [BaselineProfileVariantConfiguration.saveInSrc] is `true`.
     * Note that the dir specified here is created in the `src/<variant>/` folder.
     */
    var baselineProfileOutputDir: String?

    /**
     * Specifies if baseline profile files should be merged into a single one when generating for
     * multiple variants:
     *  - When `true` all the generated baseline profile for each variant are merged into
     *      `src/main/generated/baselineProfiles`'.
     *  - When `false` each variant will have its own baseline profile in
     *      `src/<variant>/generated/baselineProfiles`'.
     *  If this is not specified, by default it will be true for library modules and false for
     *  application modules.
     *  Note that when `saveInSrc` is false the output folder is in the build output folder but
     *  this setting still determines whether the profile included in the built apk or
     *  aar includes all the variant profiles.
     */
    var mergeIntoMain: Boolean?

    /**
     * Specifies a filtering rule to decide which profiles rules should be included in this
     * consumer baseline profile. This is useful especially for libraries, in order to exclude
     * profile rules for class and methods for dependencies of the sample app. The filter supports:
     *  - Double wildcards, to match specified package and subpackages. Example: `com.example.**`
     *  - Wildcards, to match specified package only. Example: `com.example.*`
     *  - Class names, to match the specified class. Example: `com.example.MyClass`
     *
     * Note that when only excludes are specified, if there are no matches with any rule the profile
     * rule is selected.
     *
     * Example to include a package and all the subpackages:
     * ```
     *     filter { include "com.somelibrary.**" }
     * ```
     *
     * Example to exclude some packages and include all the rest:
     * ```
     *     filter { exclude "com.somelibrary.debug" }
     * ```
     *
     * Example to include and exclude specific packages:
     * ```
     *     filter {
     *          include "com.somelibrary.widget.grid.**"
     *          exclude "com.somelibrary.widget.grid.debug.**"
     *          include "com.somelibrary.widget.list.**"
     *          exclude "com.somelibrary.widget.grid.debug.**"
     *          include "com.somelibrary.widget.text.**"
     *          exclude "com.somelibrary.widget.grid.debug.**"
     *     }
     * ```
     */
    fun filter(action: FilterRules.() -> (Unit))

    /**
     * Specifies a filtering rule to decide which profiles rules should be included in this
     * consumer baseline profile. This is useful especially for libraries, in order to exclude
     * profile rules for class and methods for dependencies of the sample app. The filter supports:
     *  - Double wildcards, to match specified package and subpackages. Example: `com.example.**`
     *  - Wildcards, to match specified package only. Example: `com.example.*`
     *  - Class names, to match the specified class. Example: `com.example.MyClass`
     *
     * Note that when only excludes are specified, if there are no matches with any rule the profile
     * rule is selected.
     *
     * Example to include a package and all the subpackages:
     * ```
     *     filter { include "com.somelibrary.**" }
     * ```
     *
     * Example to exclude some packages and include all the rest:
     * ```
     *     filter { exclude "com.somelibrary.debug" }
     * ```
     *
     * Example to include and exclude specific packages:
     * ```
     *     filter {
     *          include "com.somelibrary.widget.grid.**"
     *          exclude "com.somelibrary.widget.grid.debug.**"
     *          include "com.somelibrary.widget.list.**"
     *          exclude "com.somelibrary.widget.list.debug.**"
     *          include "com.somelibrary.widget.text.**"
     *          exclude "com.somelibrary.widget.text.debug.**"
     *     }
     * ```
     */
    fun filter(action: Action<FilterRules>)

    /**
     * Allows to specify a target `com.android.test` module that has the `androidx.baselineprofile`
     * plugin, and that can provide a baseline profile for this module. For example
     * ```
     * baselineProfile {
     *     variants {
     *         freeRelease {
     *             from(project(":baseline-profile"))
     *         }
     *     }
     * }
     * ```
     */
    fun from(project: Project) = from(project, null)

    /**
     * Allows to specify a target `com.android.test` module that has the `androidx.baselineprofile`
     * plugin, and that can provide a baseline profile for this module. The [variantName] can
     * directly map to a test variant, to fetch a baseline profile for a different variant.
     * For example it's possible to use a `paidRelease` baseline profile for `freeRelease` variant.
     * ```
     * baselineProfile {
     *     variants {
     *         freeRelease {
     *             from(project(":baseline-profile"), "paidRelease")
     *         }
     *     }
     * }
     * ```
     */
    fun from(project: Project, variantName: String?)
}

class FilterRules {

    internal val rules = mutableListOf<Pair<RuleType, String>>()

    fun include(pkg: String) = rules.add(Pair(RuleType.INCLUDE, pkg))
    fun exclude(pkg: String) = rules.add(Pair(RuleType.EXCLUDE, pkg))
}

enum class RuleType {
    INCLUDE,
    EXCLUDE
}
