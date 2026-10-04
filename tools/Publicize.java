import org.objectweb.asm.*;
import java.io.*;
import java.util.zip.*;

/** Makes every class/member in a jar public (what Fabric's runtime does for remapped package access). */
public class Publicize {
    static int pub(int access) {
        return (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
    }
    public static void main(String[] a) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(a[0]));
             ZipOutputStream out = new ZipOutputStream(new FileOutputStream(a[1]))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                byte[] data = in.readAllBytes();
                if (e.getName().endsWith(".class")) {
                    ClassReader cr = new ClassReader(data);
                    ClassWriter cw = new ClassWriter(0);
                    cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                        public void visit(int v, int acc, String n, String s, String sup, String[] i) { super.visit(v, pub(acc), n, s, sup, i); }
                        public void visitInnerClass(String n, String o, String in2, int acc) { super.visitInnerClass(n, o, in2, pub(acc)); }
                        public FieldVisitor visitField(int acc, String n, String d, String s, Object v) { return super.visitField(pub(acc), n, d, s, v); }
                        public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] x) {
                            int na = n.equals("<clinit>") ? acc : pub(acc);
                            return super.visitMethod(na, n, d, s, x);
                        }
                    }, 0);
                    data = cw.toByteArray();
                }
                out.putNextEntry(new ZipEntry(e.getName()));
                out.write(data);
                out.closeEntry();
            }
        }
    }
}
