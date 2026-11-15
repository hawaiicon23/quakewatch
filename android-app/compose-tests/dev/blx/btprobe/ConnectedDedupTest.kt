package dev.blx.btprobe
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ConnectedDedupTest {
    @Test fun completedDataMessagesDiscardAdvertisingAndNotifyOnce(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        // Use an isolated cache database; never alter application conversation history.
        val context=object:android.content.ContextWrapper(instrumentation.targetContext){
            override fun getDatabasePath(name:String)=java.io.File(cacheDir,"dedup-test-$name")
            override fun openOrCreateDatabase(name:String,mode:Int,factory:android.database.sqlite.SQLiteDatabase.CursorFactory?)=
                android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name:String,mode:Int,factory:android.database.sqlite.SQLiteDatabase.CursorFactory?,handler:android.database.DatabaseErrorHandler?)=
                android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,handler)
            override fun deleteDatabase(name:String)=android.database.sqlite.SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        }
        instrumentation.runOnMainSync{
            context.deleteDatabase("chat.db")
            var notified=0
            val host=object:ChatEngine.Host{
                override fun sender()=2
                override fun sendImage(id:Int,envelope:ByteArray,expected:Collection<Int>,done:BulkImages.Completion)=false
                override fun cancelImageTransfers(){}
                override fun imageAdvertising()=true
                override fun connectedImageAvailable(expected:Collection<Int>)=false
                override fun wake(){}
                override fun chatChanged(){}
                override fun incomingMessage(name:String,from:Int,preview:String){notified++}
            }
            val engine=ChatEngine(context,host)
            try{
                val text=ChatProtocol.envelope(ChatProtocol.TEXT,"Test","One delivery across two paths".toByteArray())
                val fragments=ChatProtocol.fragment2(1,678,text)
                engine.received(ChatProtocol.decode(fragments.first()),-50)
                assertFalse(engine.previews().isEmpty())
                assertTrue(engine.saveConnectedImage(1,678,text))
                assertTrue(engine.previews().isEmpty())
                val version=engine.historyVersion
                repeat(2){fragments.forEach{engine.received(ChatProtocol.decode(it),-50)}}
                assertTrue(engine.previews().isEmpty());assertEquals(version,engine.historyVersion);assertEquals(1,notified)
                assertTrue(engine.saveConnectedImage(1,678,text));assertEquals(1,notified)
                val conflicting=ChatProtocol.envelope(ChatProtocol.TEXT,"Test","Different content".toByteArray())
                assertFalse(engine.saveConnectedImage(1,678,conflicting))
                assertEquals(1,engine.store.entries().size)
            }finally{engine.close();context.deleteDatabase("chat.db")}
        }
    }
}
