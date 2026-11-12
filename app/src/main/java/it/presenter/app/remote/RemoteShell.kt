package it.presenter.app.remote

/**
 * Commands the SSH terminal runs on the server, written for `sh` (the user's shell may be bash, zsh,
 * fish: the line it receives is just `exec sh -c '…' …`).
 *
 * - With tmux (server option, if the command exists): each tab has its own session `presenter-<id>`,
 *   resumed if it exists, otherwise created, with the mouse on (swiping scrolls tmux's history). Once
 *   detached for more than [TMUX_IDLE_S] seconds it closes by itself: on detach tmux starts a wait that
 *   then checks that nobody has reattached meanwhile.
 * - Without tmux: the shell starts in the last folder and reports its PID; the current folder is read
 *   on a separate channel (`/proc` on Linux, `lsof` on macOS), without writing anything into the shell.
 *
 * Messages for the app arrive in the terminal stream as `ESC ] 7777 ; … BEL` sequences, which the
 * terminal ignores ([Markers]).
 *
 * `tmux -u`: the app's terminal is UTF-8, but the locale variables are not set yet at this point (the
 * login shell sets them) and tmux would replace non-ASCII characters with `_`.
 */
object RemoteShell {
    const val TMUX_IDLE_S = 86_400

    /** Name of a tab's tmux session (stable: the tab id is saved in the session). */
    fun tmuxSession(tabId: String) = "presenter-" + tabId.filter { it.isLetterOrDigit() }.take(12)

    private val SCRIPT = """
        [ -n "${'$'}2" ] && cd -- "${'$'}2" 2>/dev/null
        if [ -n "${'$'}1" ]; then
          if command -v tmux >/dev/null 2>&1; then
            printf '\033]7777;tmux\007'
            exec tmux -u new-session -A -s "${'$'}1" \; set-option mouse on \; set-hook client-detached "${'$'}3"
          fi
          printf '\033]7777;notmux\007'
        fi
        printf '\033]7777;pid=%s\007' "${'$'}${'$'}"
        exec "${'$'}{SHELL:-/bin/sh}" -l
    """.trimIndent()

    /**
     * Line run instead of the shell: [tmux] is the session name (null = no tmux), [dir] the starting folder
     * (empty = home; relative = inside the home).
     */
    fun launcher(tmux: String?, dir: String, idle: Int = TMUX_IDLE_S): String {
        val d = when {
            dir == "~" -> ""
            dir.startsWith("~/") -> dir.removePrefix("~/")
            else -> dir
        }
        val hook = tmux?.let { expiryHook(it, idle) }.orEmpty()
        return "exec sh -c ${quote(SCRIPT)} presenter ${quote(tmux.orEmpty())} ${quote(d)} ${quote(hook)}"
    }

    /**
     * tmux detach command: after [TMUX_IDLE_S] seconds it closes the session if it is still detached and
     * nobody has attached since (each detach starts its own wait; those overtaken by a reattach do
     * nothing). `run-shell` expands formats: `##` becomes `#`.
     */
    internal fun expiryHook(session: String, idle: Int = TMUX_IDLE_S): String {
        val check = "sleep $idle; t=\$(tmux display -p -t =$session: \"#{session_attached} #{session_last_attached}\") || exit 0; " +
            "set -- \$t; [ \"\$1\" = 0 ] && [ \$(( \$(date +%s) - \$2 )) -ge $idle ] && tmux kill-session -t =$session"
        return "run-shell -b '" + check.replace("#", "##") + "'"
    }

    /**
     * Closes tmux session [session]. The `=name` target must be quoted: these commands go through the
     * user's shell, and zsh expands `=word` as a command path (and fails).
     */
    fun killTmux(session: String) = "tmux kill-session -t ${quote("=$session")} 2>/dev/null"

    /** Current folder of process [pid] (Linux, then macOS); prints the path or nothing. */
    fun cwdQuery(pid: Int) =
        "readlink /proc/$pid/cwd 2>/dev/null || PATH=\"\$PATH:/usr/sbin:/sbin\" lsof -a -p $pid -d cwd -Fn 2>/dev/null | sed -n 's/^n//p'"

    /** Current folder of the active pane of tmux session [session]. */
    fun tmuxDirQuery(session: String) = "tmux display -p -t ${quote("=$session:")} '#{pane_current_path}' 2>/dev/null"

    /** Physical path of [dir] (symbolic links resolved, like the one read from `/proc`). */
    fun resolveDirQuery(dir: String) = "cd -- ${quote(dir)} 2>/dev/null && pwd -P"

    /** Path without the trailing slash (the root stays "/"). */
    fun normalizeDir(dir: String) = dir.trimEnd('/').ifEmpty { "/" }

    /**
     * Terminal to reuse for "Terminal here": the first of [dirs] (terminal folders, most recent first) that
     * is exactly in folder [target] (not in a subfolder: user's choice). -1 if none: a new one is opened,
     * even if another one is idle at the prompt.
     */
    fun pickTerminal(target: String, dirs: List<String?>): Int {
        val t = normalizeDir(target)
        return dirs.indexOfFirst { it != null && normalizeDir(it) == t }
    }

    /** Single-quoted, for sh and the common login shells (bash, zsh, fish, csh). */
    fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Finds the `ESC ] 7777 ; text BEL` sequences in the stream, even when split across reads. */
    class Markers {
        private var matched = 0
        private val payload = StringBuilder()

        fun feed(bytes: ByteArray, offset: Int, length: Int): List<String> {
            var found: MutableList<String>? = null
            for (i in offset until offset + length) {
                val b = bytes[i]
                if (matched < PREFIX.size) {
                    matched = when (b) {
                        PREFIX[matched] -> matched + 1
                        PREFIX[0] -> 1
                        else -> 0
                    }
                } else if (b == BEL) {
                    (found ?: mutableListOf<String>().also { found = it }).add(payload.toString())
                    reset()
                } else if (b < 0x20 || payload.length >= MAX) {
                    reset()
                    if (b == PREFIX[0]) matched = 1
                } else {
                    payload.append(b.toInt().toChar())
                }
            }
            return found ?: emptyList()
        }

        private fun reset() {
            matched = 0
            payload.clear()
        }

        private companion object {
            val PREFIX = "\u001b]7777;".toByteArray()
            const val BEL: Byte = 7
            const val MAX = 64
        }
    }
}
