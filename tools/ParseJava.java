import java.io.File;
import java.util.*;
import javax.tools.*;
import com.sun.source.util.JavacTask;

/** Syntax validation only; deliberately does not pretend to replace Android compilation. */
public final class ParseJava {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        try(StandardJavaFileManager manager=compiler.getStandardFileManager(diagnostics,null,null)) {
            List<File> files=new ArrayList<>(); for(String arg:args) files.add(new File(arg));
            JavacTask task=(JavacTask)compiler.getTask(null,manager,diagnostics,Arrays.asList("-proc:none","-source","8"),null,manager.getJavaFileObjectsFromFiles(files));
            task.parse();
            for(Diagnostic<?> d:diagnostics.getDiagnostics()) if(d.getKind()==Diagnostic.Kind.ERROR) throw new AssertionError(d.toString());
            System.out.println("PASS: Java syntax parsed for "+files.size()+" files (Android type checking still requires SDK)");
        }
    }
}
