package com.bbdown.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/**
 * 本地调试服务器:同一 WiFi 下浏览器访问 http://<手机IP>:19865/ 实时查看调试日志与崩溃日志。
 * 设置中开关控制,默认关闭(防局域网他人访问)。
 * 端点:
 *  - /                    HTML 实时面板(日志自动轮询 + 崩溃日志管理)
 *  - /json                状态(版本/日志水位/崩溃日志列表)
 *  - /logs?tail=N         最近 N 条日志(JSON)
 *  - /logs?after=<seq>    增量日志(seq 之后,无新日志立即返回空数组)
 *  - /logs?clear=1        清空内存日志(管理操作)
 *  - /crash               崩溃日志列表(JSON: name/size/mtime)
 *  - /crash?view=<name>   查看崩溃日志内容(纯文本)
 *  - /crash?delete=<name> 删除崩溃日志
 */
object DebugServer {
    const val PORT = 19865

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    private val lock = Any()
    @Volatile private var appContext: android.content.Context? = null

    /** 获取手机局域网 IPv4(遍历网络接口,取第一个 site-local 地址;失败回退 127.0.0.1) */
    fun lanIp(): String {
        try {
            val en = java.net.NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val ni = en.nextElement()
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val ia = addrs.nextElement()
                    if (!ia.isLoopbackAddress && ia is java.net.Inet4Address && ia.isSiteLocalAddress) {
                        return ia.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    fun start(ctx: android.content.Context) {
        synchronized(lock) {
            if (running) return
            running = true
            appContext = ctx.applicationContext
            Thread({
                try {
                    server = ServerSocket(PORT, 8, InetAddress.getByName("0.0.0.0"))
                    val ip = lanIp()
                    Logger.i("Debug", "调试服务器已启动: http://$ip:$PORT/")
                    while (running) {
                        val sock = server?.accept() ?: break
                        Thread({ handle(sock) }, "DebugConn").apply { isDaemon = true; start() }
                    }
                } catch (e: Exception) {
                    Logger.w("Debug", "调试服务器退出: ${e.message}")
                } finally {
                    running = false
                }
            }, "DebugServer").apply { isDaemon = true; start() }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    fun isRunning(): Boolean = running

    private fun handle(sock: java.net.Socket) {
        try {
            sock.soTimeout = 30_000
            val line = sock.getInputStream().bufferedReader(Charsets.UTF_8).readLine() ?: return
            val parts = line.split(" ")
            val path = parts.getOrNull(1) ?: "/"
            val query = path.substringAfter('?', "")
            val route = path.substringBefore('?')

            when {
                route == "/json" -> respond(sock, statusJson(), "application/json")
                route == "/logs" -> respond(sock, logsJson(query), if (query.contains("view=")) "text/plain" else "application/json")
                route == "/crash" -> respond(sock, crashRoute(query), if (query.contains("view=")) "text/plain" else "application/json")
                else -> respond(sock, statusHtml(), "text/html")
            }
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun respond(sock: java.net.Socket, body: String, ct: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        sock.getOutputStream().use { out ->
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: $ct; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8))
            out.write(bytes)
        }
    }

    // ===== 数据 =====

    private fun crashDir(): File? = appContext?.getExternalFilesDir(null)?.let { File(it, "logs") }

    private fun crashList(): List<File> {
        val dir = crashDir() ?: return emptyList()
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.name.endsWith(".txt")
            && (f.name.startsWith("crash_") || f.name.startsWith("native_crash_") || f.name.startsWith("native_signal_")) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    private fun crashRoute(query: String): String {
        val view = Regex("view=([^&]+)").find(query)?.groupValues?.get(1)?.take(200)
        if (view != null) {
            val f = crashList().firstOrNull { it.name == view } ?: return "崩溃日志不存在: $view"
            return try { f.readText(Charsets.UTF_8) } catch (e: Exception) { "读取失败: ${e.message}" }
        }
        val del = Regex("delete=([^&]+)").find(query)?.groupValues?.get(1)?.take(200)
        if (del != null) {
            val f = crashList().firstOrNull { it.name == del }
            if (f != null) {
                return try {
                    f.delete()
                    Logger.i("Debug", "已删除崩溃日志: ${f.name}")
                    """{"ok":true,"deleted":"${f.name}"}"""
                } catch (e: Exception) {
                    """{"ok":false,"msg":"${e.message}"}"""
                }
            }
            return """{"ok":false,"msg":"崩溃日志不存在"}"""
        }
        val arr = JSONArray()
        crashList().forEach { f ->
            arr.put(JSONObject().apply {
                put("name", f.name)
                put("size", f.length())
                put("mtime", f.lastModified())
            })
        }
        return arr.toString()
    }

    private fun historyFiles(): List<File> {
        val dir = crashDir() ?: return emptyList()
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.name.startsWith("debug_") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /** 历史日志文件名白名单（防路径穿越） */
    private fun isHistoryName(name: String): Boolean = name.matches(Regex("debug_[0-9-]+\\.log"))

    private fun logsJson(query: String): String {
        if (query.contains("clear=1")) {
            Logger.clear()
            Logger.i("Debug", "调试面板清空了内存日志")
            return """{"ok":true}"""
        }
        val view = Regex("view=([^&]+)").find(query)?.groupValues?.get(1)?.take(200)
        if (view != null) {
            if (!isHistoryName(view)) return "非法文件名"
            val dir = crashDir() ?: return "日志目录不存在"
            val f = java.io.File(dir, view)
            if (!f.exists()) return "历史日志不存在: $view"
            return try {
                // 超过 2MB 只读末尾 2MB，避免一次性读爆内存
                if (f.length() > 2L * 1024 * 1024) {
                    f.inputStream().use { ins ->
                        ins.skip(f.length() - 2L * 1024 * 1024)
                        ins.readBytes().toString(Charsets.UTF_8)
                    }
                } else f.readText(Charsets.UTF_8)
            } catch (e: Exception) { "读取失败: ${e.message}" }
        }
        val del = Regex("delete=([^&]+)").find(query)?.groupValues?.get(1)?.take(200)
        if (del != null) {
            if (!isHistoryName(del)) return """{"ok":false,"msg":"非法文件名"}"""
            val dir = crashDir() ?: return """{"ok":false,"msg":"日志目录不存在"}"""
            val f = java.io.File(dir, del)
            if (f.exists()) {
                f.delete()
                Logger.i("Debug", "已删除历史日志: $del")
                return """{"ok":true,"deleted":"$del"}"""
            }
            return """{"ok":false,"msg":"文件不存在"}"""
        }
        val after = Regex("after=(\\d+)").find(query)?.groupValues?.get(1)?.toLongOrNull()
        if (after != null) {
            val arr = JSONArray()
            Logger.since(after).forEach { l -> arr.put(logJson(l)) }
            return arr.toString()
        }
        val tail = Regex("tail=(\\d+)").find(query)?.groupValues?.get(1)?.toIntOrNull() ?: 200
        val arr = JSONArray()
        Logger.recentLines(tail).forEach { l -> arr.put(logJson(l)) }
        return arr.toString()
    }

    private fun logJson(l: LogLine): JSONObject = JSONObject().apply {
        put("seq", l.seq)
        put("time", l.time)
        put("level", l.level)
        put("tag", l.tag)
        put("msg", l.msg)
    }

    private fun statusJson(): String {
        val arr = JSONArray()
        crashList().forEach { f ->
            arr.put(JSONObject().apply {
                put("name", f.name)
                put("size", f.length())
                put("mtime", f.lastModified())
            })
        }
        val hist = JSONArray()
        historyFiles().forEach { f ->
            hist.put(JSONObject().apply {
                put("name", f.name)
                put("size", f.length())
                put("mtime", f.lastModified())
            })
        }
        return JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("version", com.bbdown.app.BuildConfig.VERSION_NAME)
            put("logCount", Logger.getCount())
            put("logSeq", Logger.maxSeq())
            put("debugServer", running)
            put("crash", arr)
            put("history", hist)
        }.toString()
    }

    // ===== HTML 面板 =====

    private fun statusHtml(): String {
        return """<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>BBDown 调试面板</title>
<style>
body{font-family:system-ui,sans-serif;background:#1e1e1e;color:#ccc;margin:0;padding:16px;font-size:13px}
h1{font-size:18px;color:#FB7299;margin:0 0 4px}
.sub{color:#969696;font-size:12px;margin-bottom:12px}
h2{font-size:13px;color:#bbb;border-bottom:1px solid #333;padding-bottom:6px;margin:18px 0 10px}
.card{background:#252526;border:1px solid #333;border-radius:8px;padding:12px 16px;margin-bottom:8px}
.row{display:flex;justify-content:space-between;gap:12px;padding:3px 0;font-size:13px}
.row .k{color:#969696;flex:0 0 auto}.row .v{color:#e8e8e8;word-break:break-all;text-align:right}
.ok{color:#89d185}.off{color:#f48771}
.log{font-family:monospace;font-size:11px;line-height:1.6;background:#1b1b1f;border-radius:6px;padding:10px;height:300px;overflow-y:auto;white-space:pre-wrap;word-break:break-all}
.log .E{color:#f48771}.log .W{color:#d7ba7d}.log .D{color:#6cc7f0}
.crash-item{display:flex;justify-content:space-between;align-items:center;gap:10px;padding:8px 12px;background:#252526;border:1px solid #333;border-radius:6px;margin-bottom:6px;font-size:12px}
.crash-item a{color:#FB7299;text-decoration:none}
.btn{background:#333;color:#ccc;border:none;border-radius:6px;padding:4px 10px;font-size:12px;cursor:pointer}
.btn.del{background:#3a1f1f;color:#f48771}
a{color:#FB7299}
</style></head><body>
<h1>BBDown 调试面板 <span id="ver"></span></h1>
<div class="sub">自动每 2 秒刷新 · 接口: <a href="/json">/json</a> <a href="/logs?tail=200">/logs</a> <a href="/crash">/crash</a></div>
<h2>状态</h2>
<div class="card" id="stateCard">加载中…</div>
<h2>崩溃日志</h2>
<div id="crashList">加载中…</div>
<h2>历史日志</h2>
<div id="histList">加载中…</div>
<h2>实时日志 <span id="logState" class="off">连接中</span></h2>
<div class="log" id="logBox"></div>
<div style="margin-top:8px"><button class="btn" onclick="clearLogs()">清空日志</button></div>
<script>
let logSeq = 0;
function esc(s){return String(s??'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));}
function fmtSize(b){if(!b)return'0 B';const u=['B','KB','MB','GB'];let i=0;while(b>=1024&&i<3){b/=1024;i++;}return b.toFixed(i?1:0)+' '+u[i];}
function fmtTime(t){const d=new Date(t);return ('0'+(d.getMonth()+1)).slice(-2)+'-'+('0'+d.getDate()).slice(-2)+' '+('0'+d.getHours()).slice(-2)+':'+('0'+d.getMinutes()).slice(-2);}
async function pollState(){
  try{
    const s=await (await fetch('/json')).json();
    document.getElementById('ver').textContent='v'+s.version;
    document.getElementById('stateCard').innerHTML=
      '<div class="row"><span class="k">版本</span><span class="v">'+esc(s.version)+'</span></div>'+
      '<div class="row"><span class="k">调试服务器</span><span class="v '+(s.debugServer?'ok':'off')+'">'+(s.debugServer?'运行中':'已停止')+'</span></div>'+
      '<div class="row"><span class="k">内存日志</span><span class="v">'+s.logCount+' 条</span></div>';
    document.getElementById('crashList').innerHTML = (s.crash||[]).length
      ? s.crash.map(c=>'<div class="crash-item"><a href="/crash?view='+esc(c.name)+'" target="_blank">'+esc(c.name)+'</a>'+
          '<span style="color:#969696">'+fmtSize(c.size)+' · '+fmtTime(c.mtime)+'</span>'+
          '<button class="btn del" onclick="delCrash(\''+esc(c.name)+'\')">删除</button></div>').join('')
      : '<div class="card" style="color:#969696">暂无崩溃日志</div>';
    document.getElementById('histList').innerHTML = (s.history||[]).length
      ? s.history.map(h=>'<div class="crash-item"><a href="/logs?view='+esc(h.name)+'" target="_blank">'+esc(h.name)+'</a>'+
          '<span style="color:#969696">'+fmtSize(h.size)+' · '+fmtTime(h.mtime)+'</span>'+
          '<button class="btn del" onclick="delHist(\''+esc(h.name)+'\')">删除</button></div>').join('')
      : '<div class="card" style="color:#969696">暂无历史日志</div>';
  }catch(e){}
}
async function pollLogs(){
  try{
    const arr=await (await fetch('/logs?after='+logSeq)).json();
    if(arr.length){ logSeq=arr[arr.length-1].seq; const box=document.getElementById('logBox');
      arr.forEach(l=>{ const div=document.createElement('div'); div.className=l.level;
        div.textContent='['+l.time+']['+l.level+']['+l.tag+'] '+l.msg; box.appendChild(div); });
      while(box.children.length>500) box.removeChild(box.firstChild);
      box.scrollTop=box.scrollHeight;
      document.getElementById('logState').textContent='实时'; document.getElementById('logState').className='ok';
    }
  }catch(e){ document.getElementById('logState').textContent='断开'; document.getElementById('logState').className='off'; }
}
async function clearLogs(){
  await fetch('/logs?clear=1'); logSeq=0;
  document.getElementById('logBox').innerHTML='';
  document.getElementById('logState').textContent='已清空'; document.getElementById('logState').className='ok';
}
async function delCrash(name){
  if(!confirm('删除崩溃日志 '+name+' ?')) return;
  await fetch('/crash?delete='+encodeURIComponent(name));
  pollState();
}
async function delHist(name){
  if(!confirm('删除历史日志 '+name+' ?')) return;
  await fetch('/logs?delete='+encodeURIComponent(name));
  pollState();
}
setInterval(pollState, 2000); setInterval(pollLogs, 2000);
pollState(); pollLogs();
</script>
</body></html>"""
    }
}
