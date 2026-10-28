package com.roziq.zixtheserver

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream

/** Membuat key RSA baru, memasangnya di authorized_keys server, lalu memakainya untuk login. */
object KeyInstaller {

    /** Melempar exception jika gagal. Mengembalikan data server yang sudah memakai key. */
    fun install(store: ServerStore, server: Server): Server {
        val kp = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 3072)
        val priv = ByteArrayOutputStream()
        kp.writePrivateKey(priv)
        val pubOut = ByteArrayOutputStream()
        kp.writePublicKey(pubOut, "zixtheserver")
        kp.dispose()
        val pub = pubOut.toString("UTF-8").trim()

        val session = store.open(server)
        try {
            val q = Tools.q(pub)
            val cmd = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
                "(grep -qxF $q ~/.ssh/authorized_keys 2>/dev/null || echo $q >> ~/.ssh/authorized_keys) && " +
                "chmod 600 ~/.ssh/authorized_keys"
            val r = Ssh.execCommand(session, cmd)
            if (r.exit != 0) throw IllegalStateException(r.output.take(200).ifBlank { "exit ${r.exit}" })
        } finally {
            Ssh.disconnect(session)
        }

        val updated = server.copy(privateKey = priv.toString("UTF-8"), password = "")
        // Uji login memakai key sebelum menyimpan, agar tidak terkunci dari server.
        Ssh.disconnect(store.open(updated))
        store.save(updated)
        return updated
    }
}
