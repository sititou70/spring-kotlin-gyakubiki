import com.intellij.ide.hierarchy.HierarchyBrowserBaseEx
import com.intellij.ide.hierarchy.call.CallHierarchyNodeDescriptor
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages.showErrorDialog
import com.intellij.openapi.ui.Messages.showInfoMessage
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.source.xml.XmlTagImpl
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScopes
import io.ktor.util.encodeBase64
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.hierarchy.calls.KotlinCallHierarchyNodeDescriptor
import org.jetbrains.kotlin.idea.hierarchy.calls.KotlinCallerTreeStructure
import org.jetbrains.kotlin.idea.util.getLineCountByDocument
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.getChildrenOfType
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import java.io.File

// utils
fun printPsiElement(element: PsiElement): String {
    val path = element.containingFile.virtualFile.path
    val line = element.getLineCountByDocument(0, element.startOffset)
    val name =
        if (element is KtNamedFunction) {
            element.name
        } else {
            "${element.javaClass.name} ${System.identityHashCode(element).toString(16)}"
        }

    return "$name ($path:$line)"
}

fun findCallerElements(element: PsiElement): List<PsiElement> {
    if (element !is KtElement) {
        showErrorDialog(printPsiElement(element), "FindCallerElements: Element Is Not KtElement")
        return listOf()
    }

    val treeStructure = KotlinCallerTreeStructure(element, HierarchyBrowserBaseEx.SCOPE_PROJECT)
    val childElements = treeStructure.getChildElements(treeStructure.rootElement)
    val psiElements = mutableListOf<PsiElement>()
    for (childElement in childElements) {
        val psiElement =
            when {
                childElement is CallHierarchyNodeDescriptor -> childElement.psiElement
                childElement is KotlinCallHierarchyNodeDescriptor -> childElement.psiElement
                else -> {
                    showErrorDialog(childElement.javaClass.name, "FindCallerElements: ChildElement Is Unknown Type")
                    continue
                }
            }
        if (psiElement == null) {
            showErrorDialog(printPsiElement(element), "FindCallerElements: PsiElement Is Null")
            continue
        }

        psiElements += psiElement
    }

    return psiElements
}

fun findCallRelationsRecursive(
    callee: PsiElement,
    callRelations: MutableMap<PsiElement, List<PsiElement>>,
) {
    if (callee in callRelations) return

    val callers = findCallerElements(callee)
    callRelations[callee] = callers

    for (caller in callers) {
        findCallRelationsRecursive(caller, callRelations)
    }
}

fun getAllNamedFunctions(project: Project): List<KtNamedFunction> {
    val namedFunctions = mutableListOf<KtNamedFunction>()

    val psiManager = PsiManager.getInstance(project)
    val virtualFiles = FileTypeIndex.getFiles(KotlinFileType.INSTANCE, GlobalSearchScopes.projectProductionScope(project))
    for (file in virtualFiles) {
        val psiFile = psiManager.findFile(file) ?: continue
        for (ktClass in psiFile.children) {
            if (ktClass !is KtClass) continue

            for (ktClassBody in ktClass.getChildrenOfType<KtClassBody>()) {
                for (ktNamedFunction in ktClassBody.getChildrenOfType<KtNamedFunction>()) {
                    namedFunctions.add(ktNamedFunction)
                }
            }
        }
    }

    return namedFunctions
}

fun findRepositoryFunctionFromXml(
    method: String,
    xmlPath: String,
    namespace: String,
    project: Project,
): KtNamedFunction? {
    val nameWithoutExtension = File(xmlPath).nameWithoutExtension
    val namedFunctions = getAllNamedFunctions(project)
    for (namedFunction in namedFunctions) {
        if (namedFunction.containingFile.virtualFile.path
                .contains(namespace.replace(".", "/")) &&
            namedFunction.containingFile.virtualFile.path
                .endsWith("$nameWithoutExtension.kt") &&
            namedFunction.name == method
        ) {
            return namedFunction
        }
    }

    return null
}

fun getQueryToRepositoryMappings(project: Project): List<List<String>> {
    val queryInfo = mutableListOf<List<String>>()

    val psiManager = PsiManager.getInstance(project)
    val virtualFiles = FileTypeIndex.getFiles(XmlFileType.INSTANCE, GlobalSearchScopes.projectProductionScope(project))
    for (file in virtualFiles) {
        val psiFile = psiManager.findFile(file) ?: continue
        for (xmlDocument in psiFile.children) {
            for (mapperTag in xmlDocument.getChildrenOfType<XmlTagImpl>()) {
                if (mapperTag.name != "mapper") continue

                val namespace = mapperTag.attributes.find { it.name == "namespace" }?.value
                if (namespace == null) {
                    showErrorDialog(printPsiElement(mapperTag), "No Namespace Attribute At MyBatis XML Mapper Tag")
                    continue
                }

                for (queryTag in mapperTag.subTags) {
                    if (queryTag.name !in listOf("select", "insert", "update", "delete")) continue

                    val method = queryTag.attributes.find { it.name == "id" }?.value
                    if (method == null) {
                        showErrorDialog(printPsiElement(queryTag), "No Id Attribute At MyBatis XML Query Tag")
                        continue
                    }

                    val repositoryFunction = findRepositoryFunctionFromXml(method, psiFile.virtualFile.path, namespace, project)
                    if (repositoryFunction == null) {
                        showErrorDialog(printPsiElement(queryTag), "Not Found Repository Function For MyBatis XML Tag")
                        continue
                    }

                    val query = queryTag.text.encodeBase64()

                    queryInfo.add(listOf(query, printPsiElement(repositoryFunction)))
                }
            }
        }
    }

    return queryInfo
}

// main

// analyse references
val allNamedFunctions =
    ProjectManager
        .getInstance()
        .openProjects
        .flatMap { project -> getAllNamedFunctions(project) }
val callRelations = mutableMapOf<PsiElement, List<PsiElement>>()
for (namedFunctions in allNamedFunctions) {
    if (!namedFunctions.containingFile.virtualFile.path
            .contains("/repository/")
    ) {
        continue
    }

    findCallRelationsRecursive(namedFunctions, callRelations)
}

val callRelationsJson =
    callRelations
        .map { entry -> listOf(entry.key) + entry.value }
        .map { elements ->
            elements
                .map { element -> printPsiElement(element).let { "\"$it\"" } }
                .joinToString(",")
                .let { "[$it]" }
        }.joinToString(",")
        .let { "[$it]" }

// analyse queries
val queryToRepositoryMappings =
    ProjectManager
        .getInstance()
        .openProjects
        .flatMap { project -> getQueryToRepositoryMappings(project) }
val queriesJson =
    queryToRepositoryMappings
        .map { queryToRepository ->
            queryToRepository
                .map { "\"$it\"" }
                .joinToString(",")
                .let { "[$it]" }
        }.joinToString(",")
        .let { "[$it]" }

val resultJson =
    """
    {
        "callRelations": $callRelationsJson,
        "queries": $queriesJson
    }
    """.trimIndent()

showInfoMessage(resultJson, "Analysis Results")
