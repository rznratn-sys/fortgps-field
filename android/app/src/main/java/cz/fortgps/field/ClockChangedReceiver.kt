package cz.fortgps.field
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
class ClockChangedReceiver: BroadcastReceiver() {
    override fun onReceive(c: Context,i: Intent) {
        if(i.action in setOf(Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED)) {
            val p=c.getSharedPreferences("photo-clock",Context.MODE_PRIVATE)
            p.edit().putLong("epoch",p.getLong("epoch",0)+1).commit()
        }
    }
}
fun clockEpoch(c: Context): Long {
    val boot=android.provider.Settings.Global.getInt(c.contentResolver,android.provider.Settings.Global.BOOT_COUNT,0)
    return (boot.toLong() shl 32) + c.getSharedPreferences("photo-clock",Context.MODE_PRIVATE).getLong("epoch",0)
}
