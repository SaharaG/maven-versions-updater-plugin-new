package com.sahara.plugin.jetbrains.maven.inspection

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.util.IntentionFamilyName
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Version
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.xml.XmlElement
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.xml.DomFileElement
import com.intellij.util.xml.highlighting.DomElementAnnotationHolder
import com.intellij.util.xml.highlighting.DomElementsInspection
import com.sahara.plugin.jetbrains.i18n.DependencyUpdaterBundle.message
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.annotations.Nullable
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomDependency
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import org.jetbrains.idea.maven.dom.references.MavenPropertyPsiReference
import org.jetbrains.idea.maven.dom.references.MavenPsiElementWrapper
import org.jetbrains.idea.maven.execution.MavenExternalParameters
import org.jetbrains.idea.maven.indices.MavenArtifactSearcher
import org.jetbrains.idea.maven.model.MavenCoordinate
import org.jetbrains.idea.maven.onlinecompletion.model.MavenDependencyCompletionItem
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.utils.MavenUtil
import java.util.Arrays
import java.util.function.Consumer
import kotlin.collections.HashSet

/**
 * maven dependency version checker
 *
 *
 * pluginMultiMap:
 * if ("".equals(groupId) || "org.apache.maven.plugins".equals(groupId) || "org.codehaus.mojo".equals(groupId)) {
 * -> groupId = null;
 *
 * @author liao
 * Create on 2020/8/10 14:10
 */
class DependencyUpdaterInspection : DomElementsInspection<MavenDomProjectModel?>(MavenDomProjectModel::class.java) {

    override fun checkFileElement(
        domFileElement: DomFileElement<MavenDomProjectModel?>?,
        holder: DomElementAnnotationHolder?
    ) {
        val projectModel = domFileElement?.rootElement ?: return
        if (null == holder) {
            return
        }

        val dependencies: MutableSet<MavenDomDependency> = HashSet()

        // dependencies in <dependencies></dependencies>
        for (dependency in projectModel.dependencies.dependencies) {
            val groupId = dependency.groupId.stringValue
            val artifactId = dependency.artifactId.stringValue
            val version = dependency.version.stringValue
            if (isAnyBlank(artifactId, groupId, version)) {
                continue
            }
            dependencies.add(dependency)
        }

        // dependencies in <dependencyManagement><dependencies></dependencies></dependencyManagement>
        for (dependency in projectModel.dependencyManagement.dependencies.dependencies) {
            val groupId = dependency.groupId.stringValue
            val artifactId = dependency.artifactId.stringValue
            val version = dependency.version.stringValue
            if (isAnyBlank(artifactId, groupId, version)) {
                continue
            }
            dependencies.add(dependency)
        }

        // todo plugin dependencies in build
        // todo parent dependency like spring boot
        checkLatestVersion(domFileElement, holder, dependencies)
    }

    override fun getGroupDisplayName(): String {
        return message("inspection.group.name")
    }

    override fun getDisplayName(): String {
        return message("inspection.plugin.maven.key")
    }

    override fun getShortName(): String {
        return message("inspection.plugin.maven.short.name")
    }

    override fun getStaticDescription(): String? {
        return message("inspection.plugin.maven.description")
    }

    override fun getDefaultLevel(): HighlightDisplayLevel {
        return HighlightDisplayLevel.WARNING
    }

    companion object {
        private val logger = Logger.getInstance(DependencyUpdaterInspection::class.java)
        private const val DEFINED_AS_PROPERTY_START = "\${"
        private const val MAVEN_VERSION_35 = "3.5"
        private const val VALUE_TO_CHECK = "\\$\\{(revision|sha1|changelist)}"
        private const val MAX_ARTIFACT_SEARCHER_RESULT = 200
        private val mavenArtifactSearcher = MavenArtifactSearcher()

        private fun checkLatestVersion(
            domFileElement: DomFileElement<MavenDomProjectModel?>,
            holder: DomElementAnnotationHolder,
            dependencies: MutableSet<MavenDomDependency>
        ) {
            val module = domFileElement.module ?: return
            dependencies.forEach(
                Consumer { dependency: MavenDomDependency ->
                    val groupId = dependency.groupId.stringValue
                    val artifactId = dependency.artifactId.stringValue
                    val version = dependency.version.stringValue
                    if (null == groupId || null == artifactId || null == version) {
                        return@Consumer
                    }

                    // remote https://package-search.services.jetbrains.com/api/search/idea/fulltext?query=${pattern}
                    val pattern = "$groupId:$artifactId:"
                    val results = mavenArtifactSearcher.search(module.project, pattern, MAX_ARTIFACT_SEARCHER_RESULT)
                    results.filter {
                        it.searchResults.groupId == groupId && it.searchResults.artifactId == artifactId
                    }.forEach {
                        val info = it.searchResults
                        val versions: Array<MavenDependencyCompletionItem> = info.items
                        Arrays.sort(versions) { i1: MavenCoordinate, i2: MavenCoordinate ->
                            val v1 = i1.version
                            val v2 = i2.version
                            assert(v1 != null)
                            ComparableVersion(v2).compareTo(ComparableVersion(v1))
                        }

                        // latest version item
                        val latest = versions[0].version
                        if (null == latest || StringUtil.isEmptyOrSpaces(latest)) {
                            return@forEach
                        }
                        if (ComparableVersion(latest) > ComparableVersion(version)) {
                            logger.info("[$groupId:$artifactId] found latest version : $latest")
                            createProblem(dependency, domFileElement, holder, groupId, artifactId, version, latest)
                        }
                    }
                }
            )
        }

        /**
         * create problem after versions check
         *
         * @param dependency    current dependency
         * @param holder        the place to store problems
         * @param model         MavenDomProjectModel
         * @param groupId       g:
         * @param artifactId    a:
         * @param version       current version
         * @param latestVersion latest version
         */
        private fun createProblem(
            dependency: MavenDomDependency,
            domFileElement: DomFileElement<MavenDomProjectModel?>,
            holder: DomElementAnnotationHolder,
            groupId: String,
            artifactId: String,
            version: String,
            latestVersion: String
        ) {
            val projectModel = domFileElement.rootElement
            val domValue = dependency.version

            // ${xx.version}
            val unresolvedValue = domValue.rawText ?: return

            val valueToCheck = if (maven35(domFileElement.manager.project)) {
                unresolvedValue.replace(VALUE_TO_CHECK.toRegex(), "")
            } else {
                unresolvedValue
            }
            logger.info("[$groupId:$artifactId] value to check : $valueToCheck")
            var fix: LocalQuickFix? = null
            if (valueToCheck.contains(DEFINED_AS_PROPERTY_START)) {

                // resolved value defined in property, 1.2.3
                var resolvedValue: @Nullable String? = domValue.stringValue ?: return
                val xmlElement = domValue.xmlElement ?: return
                if (null == resolvedValue) {
                    return
                }
                if (unresolvedValue == resolvedValue || resolvedValue.contains(DEFINED_AS_PROPERTY_START)) {
                    resolvedValue = resolveXmlElement(xmlElement)
                }

                // find reference property
                val psiReference = ContainerUtil.findInstance(
                    domValue.xmlElement!!.references,
                    MavenPropertyPsiReference::class.java
                ) ?: return
                val resolvedElement = psiReference.resolve() ?: return
                val psiElement = (resolvedElement as MavenPsiElementWrapper).wrappee
                if (unresolvedValue != resolvedValue && !StringUtil.isEmptyOrSpaces(resolvedValue)) {
                    fix = object : LocalQuickFix {
                        override fun getFamilyName(): @IntentionFamilyName String {
                            return message("inspection.plugin.replace.version.property", latestVersion)
                        }

                        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
                            if (psiElement is XmlTag) {
                                psiElement.value.text = latestVersion
                            } else if (psiElement is XmlText) {
                                psiElement.value = latestVersion
                            }
                        }
                    }
                }
            } else {
                fix = object : LocalQuickFix {
                    override fun getFamilyName(): @IntentionFamilyName String {
                        return message("inspection.plugin.replace.version.element", latestVersion)
                    }

                    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
                        val psiElement = descriptor.psiElement
                        if (psiElement is XmlTag) {
                            psiElement.value.text = latestVersion
                        } else if (psiElement is XmlText) {
                            psiElement.value = latestVersion
                        }
                    }
                }
            }
            val projectName = createLinkText(projectModel, dependency)
            holder.createProblem(
                dependency.version,
                HighlightSeverity.WARNING,
                message(
                    "inspection.plugin.version.outdated",
                    projectName,
                    groupId,
                    artifactId,
                    version,
                    latestVersion
                ),
                fix
            )
        }

        private fun createLinkText(model: MavenDomProjectModel, dependency: MavenDomDependency): String {
            val tag = dependency.xmlTag ?: return MavenDomUtil.getProjectName(model)
            val file = tag.containingFile.virtualFile ?: return MavenDomUtil.getProjectName(model)
            return String.format(
                "<a href ='#navigation/%s:%s'>%s</a>",
                file.path,
                tag.textRange.startOffset,
                MavenDomUtil.getProjectName(model)
            )
        }

        private fun resolveXmlElement(xmlElement: XmlElement): String? {
            val psiReference = ContainerUtil.findInstance(
                xmlElement.references,
                MavenPropertyPsiReference::class.java
            ) ?: return null
            val resolvedElement = psiReference.resolve() as? MavenPsiElementWrapper ?: return null
            val xmlTag = resolvedElement.wrappee
            return if (xmlTag !is XmlTag) {
                null
            } else {
                xmlTag.value.trimmedText
            }
        }

        private fun maven35(project: Project): Boolean {
            val manager = MavenProjectsManager.getInstance(project)
            val generalSettings = manager.generalSettings
            var maven35 = true
            try {
                var mavenVersion = MavenUtil.getMavenVersion(
                    MavenExternalParameters.resolveMavenHome(generalSettings, project, null)
                )
                mavenVersion = mavenVersion?.let { Version.parseVersion(it)?.toCompactString() } ?: "unknown"
                maven35 = StringUtil.compareVersionNumbers(mavenVersion, MAVEN_VERSION_35) >= 0
            } catch (ignore: Exception) {
                // ignore invalid maven home configuration
                logger.error("invalid maven home configuration")
            }
            return maven35
        }

        private fun isAnyBlank(vararg css: String?): Boolean {
            return if (css.isEmpty()) {
                false
            } else {
                css.forEach {
                    if (StringUtil.isEmptyOrSpaces(it)) {
                        return true
                    }
                }
                false
            }
        }
    }
}
