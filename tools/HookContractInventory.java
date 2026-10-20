import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.Adler32;
import java.util.zip.ZipFile;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.Method;

/** Desktop-only inventory of selected DEX contracts; never copies implementation into the APK. */
public final class HookContractInventory {
  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      throw new IllegalArgumentException("Usage: APK_OR_JAR CLASS_NAMES OUTPUT_TSV");
    }
    var wanted = new HashSet<>(Files.readAllLines(Path.of(args[1])));
    var found = new HashSet<String>();
    var allNames = new TreeSet<String>();
    var lines = new TreeSet<String>();
    for (DexFile dex : dexFiles(new File(args[0]))) {
      for (ClassDef type : dex.getClasses()) {
        String name = javaName(type.getType());
        allNames.add(name);
        if (!wanted.contains(name)) continue;
        found.add(name);
        lines.add("C\t" + name + "\t" + type.getSuperclass() + "\t" + type.getInterfaces());
        for (Field field : type.getFields()) {
          lines.add(
              "F\t"
                  + name
                  + "\t"
                  + field.getName()
                  + "\t"
                  + field.getType()
                  + "\t"
                  + field.getAccessFlags());
        }
        for (Method method : type.getMethods()) {
          List<String> parameters = new ArrayList<>();
          for (CharSequence parameter : method.getParameterTypes()) {
            parameters.add(parameter.toString());
          }
          lines.add(
              "M\t"
                  + name
                  + "\t"
                  + method.getName()
                  + "\t"
                  + parameters
                  + "\t"
                  + method.getReturnType()
                  + "\t"
                  + method.getAccessFlags());
        }
      }
    }
    Files.write(Path.of(args[2]), lines);
    var missing = new TreeSet<>(wanted);
    missing.removeAll(found);
    Files.write(Path.of(args[2] + ".missing"), missing);
    Files.write(Path.of(args[2] + ".classes"), allNames);
    System.out.println(
        "Classes found="
            + found.size()
            + "; missing="
            + missing.size()
            + "; contract rows="
            + lines.size());
  }

  private static String javaName(String descriptor) {
    return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
  }

  private static List<DexFile> dexFiles(File file) throws Exception {
    var result = new ArrayList<DexFile>();
    try (var zip = new ZipFile(file)) {
      var entries = zip.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        if (!entry.getName().endsWith(".dex")) continue;
        byte[] data;
        try (var input = zip.getInputStream(entry)) {
          data = input.readAllBytes();
        }
        if (data.length < 112) throw new IllegalArgumentException("Truncated DEX");
        if (data[4] != '0' || data[5] != '4' || data[6] != '1') {
          result.add(new DexBackedDexFile(Opcodes.getDefault(), data));
          continue;
        }
        // DEX 041 offsets are absolute within the physical container. Each logical header
        // can therefore be viewed at offset zero while leaving every referenced byte intact.
        // These temporary in-memory 039 headers are for metadata inspection only. The original
        // APK/JAR is never changed, signed, installed or distributed.
        // Specification: https://source.android.com/docs/core/runtime/dex-format#container-format
        var physical = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int offset = 0;
        while (offset < data.length) {
          int logicalSize = physical.getInt(offset + 32);
          if (physical.getInt(offset + 36) != 120
              || physical.getInt(offset + 112) != data.length
              || physical.getInt(offset + 116) != offset
              || logicalSize < 120
              || logicalSize > data.length - offset) {
            throw new IllegalArgumentException("Invalid DEX 041 container bounds");
          }
          byte[] view = data.clone();
          System.arraycopy(data, offset, view, 0, 112);
          view[4] = '0';
          view[5] = '3';
          view[6] = '9';
          var header = ByteBuffer.wrap(view).order(ByteOrder.LITTLE_ENDIAN);
          header.putInt(32, view.length);
          header.putInt(36, 112);
          header.putInt(104, view.length - 112);
          header.putInt(108, 112);
          var signature = MessageDigest.getInstance("SHA-1");
          signature.update(view, 32, view.length - 32);
          System.arraycopy(signature.digest(), 0, view, 12, 20);
          var checksum = new Adler32();
          checksum.update(view, 12, view.length - 12);
          header.putInt(8, (int) checksum.getValue());
          var dex = new DexBackedDexFile(Opcodes.getDefault(), view);
          if (dex.getClasses().size() != physical.getInt(offset + 96)) {
            throw new IllegalStateException("Logical DEX class count mismatch");
          }
          result.add(dex);
          offset += logicalSize;
        }
      }
    }
    return result;
  }
}
