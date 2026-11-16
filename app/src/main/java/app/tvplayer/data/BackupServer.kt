package app.tvplayer.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * A tiny web page on the home network, open only while Settings › Backup shows it: a phone or laptop
 * can download a backup from it or send one back. The address carries a random code, so nobody else
 * on the network can stumble on it.
 */
class BackupServer(
    private val backup: BackupRepository,
    private val scope: CoroutineScope,
    private val onEvent: (String) -> Unit,
) {
    private var socket: ServerSocket? = null
    private var job: Job? = null
    private val code = buildString { val r = SecureRandom(); repeat(6) { append("abcdefghjkmnpqrstuvwxyz23456789"[r.nextInt(31)]) } }

    /** Starts the page; returns its address, or null if there's no network. */
    fun start(): String? {
        val ip = localAddress() ?: return null
        val server = runCatching { ServerSocket(PORT) }.getOrElse { runCatching { ServerSocket(0) }.getOrNull() } ?: return null
        socket = server
        job = scope.launch(Dispatchers.IO) {
            while (isActive && !server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                launch { runCatching { handle(client) } }
            }
        }
        return "http://$ip:${server.localPort}/$code"
    }

    fun stop() {
        runCatching { socket?.close() }
        job?.cancel()
    }

    private fun handle(client: Socket) = client.use {
        client.soTimeout = 15_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }.toList()
        val (method, path) = requestLine.split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val out = client.getOutputStream()
        when {
            method == "GET" && path == "/$code" -> send(out, 200, "text/html; charset=utf-8", PAGE.replace("CODE", code).toByteArray())
            method == "GET" && path == "/$code/skyfall-backup.json" -> {
                val text = runBlocking { backup.create() }
                send(out, 200, "application/json", text.toByteArray(), "Content-Disposition: attachment; filename=\"skyfall-backup.json\"\r\n")
                onEvent("A backup was downloaded to another device.")
            }
            method == "POST" && path == "/$code/restore" -> {
                val length = headers.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                if (length <= 0 || length > MAX_UPLOAD) {
                    send(out, 400, "text/plain; charset=utf-8", "That file is empty or too big.".toByteArray())
                    return
                }
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }
                val message = try {
                    runBlocking { backup.restore(String(body, 0, read, Charsets.UTF_8)) }
                } catch (e: Exception) {
                    send(out, 400, "text/plain; charset=utf-8", (e.message ?: "That backup couldn't be read.").toByteArray())
                    return
                }
                onEvent(message)
                send(out, 200, "text/plain; charset=utf-8", message.toByteArray())
            }
            else -> send(out, 404, "text/plain", "Not found".toByteArray())
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) return bytes.toString(Charsets.UTF_8.name()).trimEnd('\r')
            bytes.write(b)
            if (bytes.size() > 8_192) return null
        }
    }

    private fun send(out: OutputStream, status: Int, type: String, body: ByteArray, extra: String = "") {
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; else -> "Not Found" }
        out.write("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private companion object {
        const val PORT = 8686
        const val MAX_UPLOAD = 5_000_000

        val PAGE = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Skyfall backup</title>
            <style>
              body{font-family:system-ui,-apple-system,sans-serif;background:#061338;color:#eef3ff;margin:0;padding:28px;max-width:560px}
              h1{font-size:26px;margin:0 0 6px} p{color:#a9b7d6;line-height:1.5}
              a.button,label.button{display:inline-block;background:#00beeb;color:#02121e;font-weight:600;padding:14px 22px;border-radius:12px;text-decoration:none;margin:8px 0;cursor:pointer}
              input{display:none} #result{margin-top:14px;font-weight:600}
              section{background:#0c2560;border-radius:16px;padding:18px 20px;margin:18px 0}
            </style></head><body>
            <h1>Skyfall backup</h1>
            <p>Your playlists (with their sign-in details), favourites, settings, watchlist and reminders.</p>
            <section><h2>Save a copy</h2><p>Keep the file somewhere safe: it contains your playlist passwords.</p>
            <a class="button" href="/CODE/skyfall-backup.json" download>Download backup</a></section>
            <section><h2>Restore</h2><p>Choose a Skyfall backup file to load it onto the Shield.</p>
            <label class="button">Choose backup file<input id="file" type="file" accept=".json,application/json"></label>
            <div id="result"></div></section>
            <script>
              document.getElementById('file').addEventListener('change', async e => {
                const f = e.target.files[0]; if (!f) return;
                const out = document.getElementById('result'); out.textContent = 'Sending…';
                try {
                  const r = await fetch('/CODE/restore', {method:'POST', body: await f.text()});
                  out.textContent = await r.text();
                } catch (err) { out.textContent = 'Couldn’t reach the Shield. Is Settings › Backup still open?'; }
              });
            </script></body></html>
        """.trimIndent()
    }
}
