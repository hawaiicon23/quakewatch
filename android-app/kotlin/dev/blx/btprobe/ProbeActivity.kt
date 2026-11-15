package dev.blx.btprobe

import android.content.Intent
import androidx.activity.ComponentActivity

open class ProbeActivity : ComponentActivity() {
    override fun onStart() { super.onStart(); Permissions.resumeIfBlocked(this) }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        Permissions.onResult(this, code)
    }
    @Deprecated("Activity result compatibility with existing permission flows")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (Permissions.onActivityResult(this, code, result)) return
        if ((code == 30 || code == 60) && result == RESULT_OK) {
            val mesh = MeshService.get()
            if (mesh?.running == true) mesh.restartRadio() else Permissions.startMesh(this, null)
        }
    }
    internal fun toggleRadio() {
        if (MeshService.get()?.running == true) startService(Intent(this, MeshService::class.java).setAction(MeshService.ACTION_STOP))
        else Permissions.startMesh(this, null)
    }
}
