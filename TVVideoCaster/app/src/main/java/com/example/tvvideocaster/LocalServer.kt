package com.example.tvvideocaster

import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Mini servidor web local para enviar el enlace desde el móvil.
 * Se abre desde el navegador del móvil (misma red WiFi): http://IP_DE_LA_TELE:8080
 * Solo está activo mientras la app está en primer plano.
 */
class LocalServer(
    private val port: Int,
    private val onUrl: (String) -> Unit
) {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start(): Boolean {
        if (running) return true
        return try {
            val s = ServerSocket(port)
            server = s
            running = true
            Thread { acceptLoop(s) }.apply { isDaemon = true }.start()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (e: Exception) { }
        server = null
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            try {
                val client = s.accept()
                Thread { handle(client) }.apply { isDaemon = true }.start()
            } catch (e: Exception) {
                if (!running) break
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { sock ->
                sock.soTimeout = 5000
                val reader = sock.getInputStream().bufferedReader(Charsets.UTF_8)
                val requestLine = reader.readLine() ?: return
                var length = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        length = line.substring(15).trim().toIntOrNull() ?: 0
                    }
                }
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: ""
                val path = parts.getOrNull(1) ?: "/"

                var sent: String? = null
                if (method == "POST" && length in 1..4096) {
                    val buf = CharArray(length)
                    var read = 0
                    while (read < length) {
                        val n = reader.read(buf, read, length - read)
                        if (n < 0) break
                        read += n
                    }
                    sent = parseForm(String(buf, 0, read))["url"]
                } else if (method == "GET" && path.startsWith("/send?")) {
                    sent = parseForm(path.substringAfter("?"))["url"]
                }

                var message = ""
                if (!sent.isNullOrBlank()) {
                    var url = sent.trim()
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://$url"
                    }
                    onUrl(url)
                    message = "<p class='ok'>&#10003; Enlace enviado a la tele. Mira la pantalla.</p>"
                }

                val body = page(message)
                val bytes = body.toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                val out = sock.getOutputStream()
                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.flush()
            }
        } catch (e: Exception) {
            // conexión cortada: se ignora
        }
    }

    private fun parseForm(data: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (pair in data.split("&")) {
            val i = pair.indexOf('=')
            if (i <= 0) continue
            try {
                map[URLDecoder.decode(pair.substring(0, i), "UTF-8")] =
                    URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            } catch (e: Exception) { }
        }
        return map
    }

    private fun page(message: String): String = """
        <!doctype html><html lang="es"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>TV Video Caster</title>
        <style>
          body{font-family:sans-serif;background:#121212;color:#fff;margin:0;padding:24px}
          h1{font-size:22px} input{width:100%;box-sizing:border-box;padding:14px;font-size:16px;
          border-radius:8px;border:1px solid #555;background:#1e1e1e;color:#fff;margin:12px 0}
          button{width:100%;padding:14px;font-size:16px;border:0;border-radius:8px;
          background:#1565C0;color:#fff} .ok{color:#6fdc8c}
        </style></head><body>
        <h1>TV Video Caster</h1>
        <p>Pega aquí el enlace de la página con el vídeo:</p>
        <form method="post" action="/">
          <input type="url" name="url" placeholder="https://..." autofocus required>
          <button type="submit">Enviar a la tele</button>
        </form>
        $message
        </body></html>
    """.trimIndent()
}
