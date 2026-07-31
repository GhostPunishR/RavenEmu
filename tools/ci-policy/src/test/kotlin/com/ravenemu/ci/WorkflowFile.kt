package com.ravenemu.ci

import java.io.File

/**
 * Lecture minimale d'un workflow GitHub Actions.
 *
 * Aucune bibliothèque YAML n'est utilisée : ce module ne doit dépendre de rien
 * d'autre que du dépôt lui-même, et les vérifications portent sur une poignée
 * de clés dont la structure est connue (jobs, `if`, `uses`, `permissions`). Un
 * découpage par indentation suffit et reste vérifiable à la lecture.
 */
class WorkflowFile(val path: File) {

    val text: String = path.readText()
    private val lines: List<String> = text.lines()

    /** Les jobs du workflow, indexés par identifiant, lignes brutes incluses. */
    val jobs: Map<String, List<String>> by lazy {
        val start = lines.indexOfFirst { it == "jobs:" }
        require(start >= 0) { "Aucune section « jobs: » dans ${path.name}" }
        childBlocks(from = start + 1, indent = 2)
    }

    /**
     * Blocs enfants d'une section : chaque clé trouvée à [indent] espaces ouvre
     * un bloc qui court jusqu'à la clé suivante de même niveau. S'arrête dès
     * qu'une ligne remonte au-dessus de [indent].
     */
    private fun childBlocks(from: Int, indent: Int): Map<String, List<String>> {
        val keyPattern = Regex("^ {$indent}([A-Za-z_][A-Za-z0-9_-]*):")
        val result = LinkedHashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (index in from until lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                current?.add(line)
                continue
            }
            val depth = line.indexOfFirst { it != ' ' }
            if (depth < indent) break
            val match = keyPattern.find(line)
            if (depth == indent && match != null) {
                current = mutableListOf(line)
                result[match.groupValues[1]] = current
            } else {
                current?.add(line)
            }
        }
        return result
    }

    /**
     * Valeur scalaire de [key] à [indent] espaces dans [block]. Gère les
     * scalaires repliés (`>-`, `>`) et littéraux (`|`, `|-`) écrits sur
     * plusieurs lignes, en joignant les lignes par une espace.
     */
    fun scalar(block: List<String>, key: String, indent: Int): String? {
        val header = Regex("^ {$indent}${Regex.escape(key)}:(.*)$")
        val start = block.indexOfFirst { header.containsMatchIn(it) }
        if (start < 0) return null
        val inline = header.find(block[start])!!.groupValues[1].trim()
        if (inline.isNotEmpty() && inline !in FOLDING_INDICATORS) return inline
        val continuation = block.asSequence()
            .drop(start + 1)
            .takeWhile { it.isBlank() || (it.indexOfFirst { c -> c != ' ' } > indent) }
            .filter { it.isNotBlank() }
            .map { it.trim() }
            .toList()
        return continuation.joinToString(" ").ifEmpty { null }
    }

    /**
     * Script shell de l'étape [stepName] du job [job], désindenté et prêt à
     * être exécuté. Permet de tester le comportement réel d'un garde-fou de
     * publication au lieu de se contenter d'en reconnaître le texte.
     */
    fun stepScript(job: String, stepName: String): String {
        val block = jobs[job] ?: error("Job « $job » absent")
        val nameIndex = block.indexOfFirst { it.contains("- name: $stepName") }
        require(nameIndex >= 0) { "Étape « $stepName » absente du job « $job »" }
        val runPattern = Regex("^( +)run: \\|-?\\s*$")
        val runIndex = (nameIndex + 1 until block.size).firstOrNull {
            runPattern.containsMatchIn(block[it])
        } ?: error("L'étape « $stepName » n'a pas de script multiligne")
        val runIndent = runPattern.find(block[runIndex])!!.groupValues[1].length
        val body = block.asSequence()
            .drop(runIndex + 1)
            .takeWhile { it.isBlank() || it.indexOfFirst { c -> c != ' ' } > runIndent }
            .toList()
        val bodyIndent = body.filter { it.isNotBlank() }
            .minOf { it.indexOfFirst { c -> c != ' ' } }
        return body.joinToString("\n") { if (it.isBlank()) it else it.substring(bodyIndent) }
    }

    /** Toutes les valeurs `uses:` du workflow, dans l'ordre du fichier. */
    val actionReferences: List<String>
        get() = lines.mapNotNull { line ->
            Regex("^\\s*(?:- )?uses:\\s*(\\S+)").find(line)?.groupValues?.get(1)
        }

    companion object {
        private val FOLDING_INDICATORS = setOf(">", ">-", "|", "|-")

        /** Racine du dépôt, trouvée en remontant depuis le dossier courant. */
        val repositoryRoot: File by lazy {
            generateSequence(File(".").absoluteFile.normalize()) { it.parentFile }
                .firstOrNull { File(it, ".github/workflows").isDirectory }
                ?: error("Racine du dépôt introuvable depuis ${File(".").absolutePath}")
        }

        fun androidWorkflow(): WorkflowFile =
            WorkflowFile(File(repositoryRoot, ".github/workflows/android.yml"))

        /**
         * Fichier de build du module [name], **quel que soit le dossier qui le
         * regroupe**.
         *
         * Les modules sont rangés par rôle (`core/`, `android/`, `tools/`), et
         * ce classement peut encore évoluer. Coder le chemin en dur ferait
         * échouer la vérification au prochain déplacement — non pas parce que
         * la règle vérifiée aurait cessé d'être vraie, mais parce que le test
         * ne saurait plus où regarder. On cherche donc le module par son nom.
         */
        fun moduleBuildFile(name: String): File {
            val candidats = repositoryRoot.walkTopDown()
                .onEnter { it.name !in setOf(".git", "build", ".gradle") }
                .filter { it.isFile && it.name == "build.gradle.kts" && it.parentFile.name == name }
                .toList()
            // Prendre le premier candidat désignerait silencieusement le mauvais
            // module le jour où deux dossiers porteraient le même nom terminal —
            // et la vérification continuerait de passer en contrôlant autre
            // chose. L'ambiguïté doit être une erreur, pas un choix arbitraire.
            return when (candidats.size) {
                1 -> candidats.single()
                0 -> error("Module « $name » introuvable sous ${repositoryRoot.path}")
                else -> error(
                    "Module « $name » ambigu : " +
                        candidats.joinToString { it.relativeTo(repositoryRoot).path }
                )
            }
        }
    }
}
