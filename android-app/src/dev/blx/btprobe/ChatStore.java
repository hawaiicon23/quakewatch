package dev.blx.btprobe;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

/** Private bounded history. Delivery state survives activity recreation. */
final class ChatStore extends SQLiteOpenHelper {
    static final class Entry {
        long row,time; int sender,id,type; boolean outgoing; String name,status; byte[] content;
    }
    ChatStore(Context c) { super(c,"chat.db",null,1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (_id INTEGER PRIMARY KEY, sender INTEGER NOT NULL, message_id INTEGER NOT NULL, time INTEGER NOT NULL, name TEXT NOT NULL, type INTEGER NOT NULL, content BLOB NOT NULL, outgoing INTEGER NOT NULL, status TEXT NOT NULL, UNIQUE(sender,message_id))");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) { throw new IllegalStateException("Unsupported history schema"); }
    long add(int sender,int id,ChatProtocol.Message message,boolean outgoing,String status) {
        android.content.ContentValues v=new android.content.ContentValues(); v.put("sender",sender); v.put("message_id",id); v.put("time",System.currentTimeMillis());
        v.put("name",message.name); v.put("type",message.type); v.put("content",message.content); v.put("outgoing",outgoing?1:0); v.put("status",status);
        SQLiteDatabase db=getWritableDatabase(); long row=db.insertWithOnConflict("messages",null,v,SQLiteDatabase.CONFLICT_IGNORE);
        if(row<0 && !contains(sender,id)) throw new IllegalStateException("Could not save chat history");
        db.execSQL("DELETE FROM messages WHERE _id NOT IN (SELECT _id FROM messages ORDER BY _id DESC LIMIT 100)");
        return row;
    }
    boolean contains(int sender,int id) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM messages WHERE sender=? AND message_id=?",new String[]{""+sender,""+id})) { return c.moveToFirst(); }
    }
    byte[] envelope(int sender,int id) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT type,name,content FROM messages WHERE sender=? AND message_id=?",new String[]{""+sender,""+id})) {
            return c.moveToFirst()?ChatProtocol.envelope(c.getInt(0),c.getString(1),c.getBlob(2)):null;
        }
    }
    boolean matches(int sender,int id,ChatProtocol.Message message) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT type,name,content FROM messages WHERE sender=? AND message_id=?",new String[]{""+sender,""+id})) {
            return c.moveToFirst()&&c.getInt(0)==message.type&&c.getString(1).equals(message.name)&&Arrays.equals(c.getBlob(2),message.content);
        }
    }
    void status(long row,String status) {
        android.content.ContentValues v=new android.content.ContentValues(); v.put("status",status); getWritableDatabase().update("messages",v,"_id=?",new String[]{""+row});
    }
    void interrupted() { getWritableDatabase().execSQL("UPDATE messages SET status='Interrupted · tap Retry' WHERE outgoing=1 AND status='Sending'"); }
    List<Entry> entries() {
        List<Entry> rows=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT _id,sender,message_id,time,name,type,content,outgoing,status FROM messages ORDER BY _id DESC LIMIT 50",null)) {
            while(c.moveToNext()) {
                Entry e=new Entry(); e.row=c.getLong(0); e.sender=c.getInt(1); e.id=c.getInt(2); e.time=c.getLong(3); e.name=c.getString(4); e.type=c.getInt(5); e.content=c.getBlob(6); e.outgoing=c.getInt(7)!=0; e.status=c.getString(8); rows.add(e);
            }
        }
        Collections.reverse(rows); return rows;
    }
}
