import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.IBinder;
import java.lang.reflect.*;
import java.io.*;

/** Read-only app_process probe. Never captures secure or protected content. */
public final class CaptureProbe {
  public static void main(String[] argv) throws Exception {
    Class<?> sc = Class.forName("android.view.SurfaceControl");
    long[] ids = (long[]) sc.getMethod("getPhysicalDisplayIds").invoke(null);
    IBinder token = (IBinder) sc.getMethod("getPhysicalDisplayToken", long.class).invoke(null, ids[0]);
    Class<?> cap = Class.forName("android.window.ScreenCaptureInternal");
    boolean layers=argv.length>1;
    Object mirror=null;
    if(layers) {
      mirror=sc.getConstructor().newInstance();
      Object wm=Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
      System.out.println("mirror="+Class.forName("android.view.IWindowManager").getMethod("mirrorDisplay",int.class,sc).invoke(wm,0,mirror));
    }
    Class<?> builder = Class.forName(cap.getName()+(layers?"$LayerCaptureArgs$Builder":"$DisplayCaptureArgs$Builder"));
    Object b = layers?builder.getConstructor(sc).newInstance(mirror):builder.getConstructor(IBinder.class).newInstance(token);
    if(layers) builder.getMethod("setChildrenOnly",boolean.class).invoke(b,false);
    builder.getMethod("setSourceCrop",Rect.class).invoke(b,new Rect(0,0,1440,140));
    builder.getMethod("setFrameScale",float.class).invoke(b,0.5f);
    // Fail closed if a secure/protected layer is encountered.
    builder.getMethod("setSecureContentPolicy",int.class).invoke(b,2);
    builder.getMethod("setProtectedContentPolicy",int.class).invoke(b,2);
    Object args = builder.getMethod("build").invoke(b);
    long start=System.nanoTime();
    Object shot=cap.getMethod(layers?"captureLayers":"captureDisplay",args.getClass()).invoke(null,args);
    if(shot==null) {System.out.println("capture returned null");return;}
    HardwareBuffer buffer=(HardwareBuffer)shot.getClass().getMethod("getHardwareBuffer").invoke(shot);
    try {
      Bitmap hardware=(Bitmap)shot.getClass().getMethod("asBitmap").invoke(shot);
      Bitmap cpu=hardware.copy(Bitmap.Config.ARGB_8888,false);
      hardware.recycle();
      System.out.println("capture+readback_ms="+(System.nanoTime()-start)/1e6+" size="+cpu.getWidth()+"x"+cpu.getHeight());
      try(FileOutputStream out=new FileOutputStream(argv[0])) {cpu.compress(Bitmap.CompressFormat.PNG,100,out);}
      cpu.recycle();
    } finally {buffer.close();if(mirror!=null) sc.getMethod("release").invoke(mirror);}
  }
}
