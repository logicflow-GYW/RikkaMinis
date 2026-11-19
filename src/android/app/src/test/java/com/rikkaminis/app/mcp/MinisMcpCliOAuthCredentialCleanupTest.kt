package com.rikkaminis.app.mcp

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * [R3-B F-10] `minis-mcp-cli` wrote and read OAuth credentials that live
 * OUTSIDE `servers.json` — `<mcp-servers>/oauth/<name>.json` (issued tokens),
 * `<name>.json.tmp` (a writer mid-rename) and `<name>.secret` (the
 * client-secret handoff `add` seeds) — but neither `remove` nor a same-name
 * `add` deleted them. `transport/http.py::_oauth_access_token` reads the bridge
 * file on EVERY call, so a server re-using the name inherited the previous
 * server's Bearer token and sent it to the new URL; and a deleted server left
 * plaintext credentials on disk.
 *
 * This class runs the REAL CLI against a temp config dir: the driver imports
 * `main.py`, points `utils.config` at a temp directory, and calls
 * `cmd_add` / `cmd_remove` directly. Assertions are about files on disk, not
 * about the source mentioning a cleanup call — the leak only exists in the
 * filesystem.
 *
 * `python3` is not present everywhere, so the class skips (never fails) when
 * it is missing, the same house pattern the mount-guard exec test uses.
 */
class MinisMcpCliOAuthCredentialCleanupTest {

    @Test
    fun `remove and same-name add leave no oauth credentials behind`() {
        val python = listOf("/usr/bin/python3", "/usr/local/bin/python3", "/bin/python3")
            .map(::File).firstOrNull { it.canExecute() }
        assumeTrue("python3 is not available", python != null)

        val dir = File(System.getProperty("java.io.tmpdir"), "minis-mcp-cli-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val driver = File(dir, "driver.py")
            driver.writeText(DRIVER)
            val proc = ProcessBuilder(python!!.absolutePath, driver.absolutePath, cliDir().absolutePath)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            val rc = proc.waitFor()
            assertEquals("the CLI driver failed:\n$out", 0, rc)

            val line = out.trim().lines().lastOrNull { it.startsWith("{") }
            assertNotNull("the driver printed no verdict:\n$out", line)
            val results = org.json.JSONObject(line!!).getJSONArray("results")
            assertTrue("the driver reported no checks:\n$out", results.length() > 0)
            val failed = buildList {
                for (i in 0 until results.length()) {
                    val row = results.getJSONObject(i)
                    println("CHECK ${if (row.optBoolean("ok", false)) "PASS" else "FAIL"} ${row.optString("label")} ${row.optString("detail")}")
                    if (!row.optBoolean("ok", false)) {
                        add("${row.optString("label")} :: ${row.optString("detail")}")
                    }
                }
            }
            assertTrue("failed checks:\n" + failed.joinToString("\n") + "\n--- driver output ---\n$out", failed.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** `assets/default_mount/usr/local/lib/minis-mcp-cli`. */
    private fun cliDir(): File {
        val rel = "src/android/app/src/main/assets/default_mount/usr/local/lib/minis-mcp-cli"
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, rel)
            if (f.isDirectory) return f
            dir = dir.parentFile
        }
        throw FileNotFoundException("$rel not found from user.dir=${System.getProperty("user.dir")}")
    }

    private companion object {
        private val DRIVER = """
            import json, os, sys

            cli_dir = sys.argv[1]
            sys.path.insert(0, cli_dir)

            import main as cli
            from utils import config

            import tempfile, shutil
            tmp = tempfile.mkdtemp(prefix="mcp-cli-test-")
            config.CONFIG_DIR = tmp
            config.CONFIG_PATH = os.path.join(tmp, "servers.json")
            oauth_dir = os.path.join(tmp, "oauth")
            os.makedirs(oauth_dir, exist_ok=True)

            emitted = []
            cli._emit = lambda obj, pretty=False: emitted.append(obj)

            results = []

            def check(label, ok, detail=""):
                results.append({"label": label, "ok": bool(ok), "detail": str(detail)})

            def run(fn, args):
                emitted.clear()
                try:
                    fn(args, False)
                except SystemExit as exc:
                    return {"exit": exc.code, "out": list(emitted)}
                return {"exit": 0, "out": list(emitted)}

            def path(name):
                return os.path.join(oauth_dir, name)

            def write(name, text):
                with open(path(name), "w", encoding="utf-8") as f:
                    f.write(text)

            def exists(name):
                return os.path.exists(path(name))

            # -- 1. add -> remove leaves nothing behind -------------------
            run(cli.cmd_add, ["--name", "alpha", "--url", "https://a.example/mcp",
                              "--oauth-client-id", "cid",
                              "--oauth-auth-endpoint", "https://a.example/auth",
                              "--oauth-token-endpoint", "https://a.example/token",
                              "--oauth-client-secret", "SECRET-ALPHA"])
            check("add seeds the client-secret handoff", exists("alpha.secret"))
            write("alpha.json", '{"access_token": "tok-alpha"}')
            write("alpha.json.tmp", '{}')
            run(cli.cmd_remove, ["alpha"])
            check("remove deletes the token bridge", not exists("alpha.json"))
            check("remove deletes the half-written bridge", not exists("alpha.json.tmp"))
            check("remove deletes the client-secret handoff", not exists("alpha.secret"))
            check("remove deletes the config entry", config.get_server("alpha") is None)

            # -- 2. same-name add must not inherit the old token ----------
            run(cli.cmd_add, ["--name", "beta", "--url", "https://b1.example/mcp"])
            write("beta.json", '{"access_token": "tok-1"}')
            run(cli.cmd_add, ["--name", "beta", "--url", "https://b2.example/mcp"])
            check("same-name add clears the previous token bridge", not exists("beta.json"))
            check("same-name add lands the new url",
                  (config.get_server("beta") or {}).get("url") == "https://b2.example/mcp")

            # -- 3. a normal add touches nobody else's credentials --------
            write("gamma.json", '{"access_token": "tok-gamma"}')
            run(cli.cmd_add, ["--name", "delta", "--url", "https://d.example/mcp"])
            check("a normal add leaves other servers' credentials alone", exists("gamma.json"))
            check("a normal add has no credentials of its own",
                  not any(exists(n) for n in ("delta.json", "delta.json.tmp", "delta.secret")))

            # -- 4. a normal oauth add still seeds its own secret ---------
            r = run(cli.cmd_add, ["--name", "eps", "--url", "https://e.example/mcp",
                                  "--oauth-client-id", "cid",
                                  "--oauth-auth-endpoint", "https://e.example/auth",
                                  "--oauth-token-endpoint", "https://e.example/token",
                                  "--oauth-client-secret", "SECRET-EPS"])
            check("a normal oauth add still seeds the client secret",
                  exists("eps.secret") and open(path("eps.secret"), encoding="utf-8").read() == "SECRET-EPS")
            check("the add still reports success",
                  bool(r["out"]) and r["out"][0].get("added") == "eps")

            # -- 5. a cleanup that cannot happen is reported, not ignored --
            # A directory in the bridge file's place makes os.remove fail even
            # as root. The add must then refuse to land: a config that is
            # visible next to the previous server's credentials is the leak.
            os.makedirs(path("zeta.json"), exist_ok=True)
            r = run(cli.cmd_add, ["--name", "zeta", "--url", "https://z.example/mcp"])
            check("an impossible cleanup exits non-zero", r["exit"] != 0, r["exit"])
            check("an impossible cleanup names the failure",
                  any("failed to clear OAuth credentials" in str(o.get("error", "")) for o in r["out"]),
                  r["out"])
            check("the add does not land while its old credentials survive",
                  config.get_server("zeta") is None)

            shutil.rmtree(tmp, ignore_errors=True)
            print(json.dumps({"results": results}))
        """.trimIndent()
    }
}
