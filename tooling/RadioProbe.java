import android.system.Os;
import android.system.OsConstants;
import android.system.ErrnoException;
import java.io.FileDescriptor;
public final class RadioProbe {
 public static void main(String[] args) {
  try {
   FileDescriptor fd=Os.socket(31,OsConstants.SOCK_RAW,1);
   System.out.println("AF_BLUETOOTH/SOCK_RAW/BTPROTO_HCI: socket opened; no commands transmitted");
   Os.close(fd);
  }catch(ErrnoException e){System.out.println("AF_BLUETOOTH/SOCK_RAW/BTPROTO_HCI: "+e.getMessage());}
 }
}
