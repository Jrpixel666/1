package com.llamaaistudio.app.data.repo

import android.content.Context
import com.llamaaistudio.app.data.db.WebProjectDao
import com.llamaaistudio.app.data.db.WebProjectEntity
import com.llamaaistudio.app.ui.webstudio.GeneratedFile
import com.llamaaistudio.app.ui.webstudio.WebCodeGen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/** Web Studio projects: HTML/CSS/JS sites (or a tiny Node server) in app-private storage. */
class WebProjectRepository(
    private val context: Context,
    private val dao: WebProjectDao,
) {
    val projectsRoot: File = File(context.filesDir, "web_projects").apply { mkdirs() }

    val projects: Flow<List<WebProjectEntity>> = dao.observeAll()

    suspend fun createProject(name: String, template: String): WebProjectEntity = withContext(Dispatchers.IO) {
        val base = name.trim().replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').ifBlank { "site" }
        var safeName = base
        var n = 2
        while (dao.countByName(safeName) > 0 || File(projectsRoot, safeName).exists()) safeName = "$base-${n++}"
        val root = File(projectsRoot, safeName).apply { mkdirs() }
        scaffold(root, safeName, template)
        val entity = WebProjectEntity(name = safeName, rootPath = root.absolutePath, template = template)
        entity.copy(id = dao.insert(entity))
    }

    suspend fun touchOpened(project: WebProjectEntity) =
        dao.update(project.copy(lastOpenedAt = System.currentTimeMillis()))

    suspend fun deleteProject(project: WebProjectEntity) = withContext(Dispatchers.IO) {
        File(project.rootPath).deleteRecursively()
        dao.delete(project)
    }

    suspend fun listFiles(root: File): List<File> = withContext(Dispatchers.IO) {
        root.walkTopDown()
            .onEnter { it == root || it.name != "node_modules" }
            .filter { it.isFile }
            .sortedBy { it.relativeTo(root).path }
            .toList()
    }

    suspend fun readFile(file: File): String = withContext(Dispatchers.IO) { file.readText() }

    suspend fun writeFile(file: File, content: String) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    suspend fun createFile(project: WebProjectEntity, relativePath: String): File? = withContext(Dispatchers.IO) {
        val target = WebCodeGen.resolveInside(File(project.rootPath), relativePath) ?: return@withContext null
        target.parentFile?.mkdirs()
        if (!target.exists()) target.writeText("")
        target
    }

    suspend fun deleteFile(project: WebProjectEntity, file: File): Boolean = withContext(Dispatchers.IO) {
        val inside = WebCodeGen.resolveInside(File(project.rootPath), file.relativeTo(File(project.rootPath)).path)
        inside != null && inside.delete()
    }

    /** Writes model output into the project; returns the files actually written. */
    suspend fun applyGenerated(project: WebProjectEntity, files: List<GeneratedFile>): List<File> =
        withContext(Dispatchers.IO) {
            val root = File(project.rootPath)
            files.mapNotNull { gen ->
                WebCodeGen.resolveInside(root, gen.path)?.also { target ->
                    target.parentFile?.mkdirs()
                    target.writeText(gen.content)
                }
            }
        }

    /** Text files fed back to the model as context, open/main file first, within a char budget. */
    suspend fun contextFiles(project: WebProjectEntity, priority: File?, budgetChars: Int): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val root = File(project.rootPath)
            val textExt = setOf("html", "htm", "css", "js", "mjs", "json", "md", "txt", "svg")
            val all = listFiles(root).filter { it.extension.lowercase() in textExt && it.length() < 64_000 }
            val ordered = listOfNotNull(priority?.takeIf { it in all }) + all.filter { it != priority }
            var used = 0
            ordered.mapNotNull { f ->
                val text = f.readText()
                if (used + text.length > budgetChars && used > 0) return@mapNotNull null
                used += text.length
                f.relativeTo(root).path to text
            }
        }

    fun mainFile(project: WebProjectEntity): File {
        val root = File(project.rootPath)
        return if (project.template == "node") File(root, "public/index.html") else File(root, "index.html")
    }

    /** Directory whose index.html the in-app offline preview opens. */
    fun previewRoot(project: WebProjectEntity): File =
        if (project.template == "node") File(project.rootPath, "public") else File(project.rootPath)

    private fun scaffold(root: File, name: String, template: String) {
        val pageRoot = if (template == "node") File(root, "public").apply { mkdirs() } else root
        File(pageRoot, "index.html").writeText(
            """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>$name</title>
              <link rel="stylesheet" href="style.css">
            </head>
            <body>
              <main>
                <h1>$name</h1>
                <p>Describe what you want in Web Studio and the AI will build it here.</p>
                <button id="hello">Say hello</button>
                <p id="out"></p>
              </main>
              <script src="script.js"></script>
            </body>
            </html>
            """.trimIndent() + "\n"
        )
        File(pageRoot, "style.css").writeText(
            """
            :root { color-scheme: light dark; font-family: system-ui, sans-serif; }
            body { margin: 0; min-height: 100vh; display: grid; place-items: center; }
            main { max-width: 40rem; padding: 1.5rem; text-align: center; }
            button { font: inherit; padding: .6rem 1.2rem; border-radius: .5rem; border: 0;
                     background: #ff8500; color: #fff; cursor: pointer; }
            """.trimIndent() + "\n"
        )
        val script = if (template == "node") {
            """
            document.getElementById('hello').addEventListener('click', async () => {
              const res = await fetch('/api/hello');
              const data = await res.json();
              document.getElementById('out').textContent = data.message;
            });
            """
        } else {
            """
            document.getElementById('hello').addEventListener('click', () => {
              document.getElementById('out').textContent = 'Hello from $name!';
            });
            """
        }
        File(pageRoot, "script.js").writeText(script.trimIndent() + "\n")

        if (template == "node") {
            File(root, "package.json").writeText(
                """
                {
                  "name": "${name.lowercase()}",
                  "version": "1.0.0",
                  "private": true,
                  "main": "server.js",
                  "scripts": { "start": "node server.js" }
                }
                """.trimIndent() + "\n"
            )
            File(root, "server.js").writeText(
                """
                // Zero-dependency Node server: static files from ./public plus a small JSON API.
                const http = require('http');
                const fs = require('fs');
                const path = require('path');

                const PORT = process.env.PORT || 8080;
                const PUBLIC = path.join(__dirname, 'public');
                const TYPES = { '.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript',
                  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg' };

                const server = http.createServer((req, res) => {
                  const url = new URL(req.url, 'http://localhost');
                  if (url.pathname === '/api/hello') {
                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    return res.end(JSON.stringify({ message: 'Hello from the $name server!', time: new Date().toISOString() }));
                  }
                  const rel = decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname);
                  const file = path.normalize(path.join(PUBLIC, rel));
                  if (!file.startsWith(PUBLIC)) { res.writeHead(403); return res.end('Forbidden'); }
                  fs.readFile(file, (err, data) => {
                    if (err) { res.writeHead(404); return res.end('Not found'); }
                    res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
                    res.end(data);
                  });
                });

                server.listen(PORT, '127.0.0.1', () => console.log('Listening on http://127.0.0.1:' + PORT));
                """.trimIndent() + "\n"
            )
        }
    }
}
