import java.io.File;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;

/** Isolated path/encoding reproduction. No PCM, window, world or settings. */
public final class ReproduceOpenALPath {
    public static void main(String[] args) throws Exception {
        // Keep evidence readable independently of the encoding under test.
        System.setOut(new PrintStream(System.out, true, "UTF-8"));
        System.setErr(new PrintStream(System.err, true, "UTF-8"));
        System.out.println("java.version=" + System.getProperty("java.version"));
        for (String key : new String[]{"file.encoding", "native.encoding", "sun.jnu.encoding", "os.arch", "java.library.path", "org.lwjgl.librarypath"})
            System.out.println(key + "=" + System.getProperty(key));
        System.out.println("Charset.defaultCharset=" + Charset.defaultCharset());
        String library = new File(args[1]).getAbsolutePath();
        System.out.println("library=" + library + "; exists=" + new File(library).isFile() + "; characters=" + library.length());
        System.out.println("preload=" + args[0]);
        URLClassLoader child = null;
        ClassLoader loader = ReproduceOpenALPath.class.getClassLoader();
        if (args[0].endsWith("-child")) {
            child = new URLClassLoader(new URL[]{new File(args[2]).toURI().toURL()}, ClassLoader.getPlatformClassLoader());
            loader = child;
        }
        Class<?> al = Class.forName("org.lwjgl.openal.AL", false, loader);
        System.out.println("al.classLoader=" + al.getClassLoader().getClass().getName());
        int code = 0;
        try {
            if (args[0].startsWith("preload")) {
                System.load(library);
                System.out.println("System.load=PASS");
            } else if (!args[0].startsWith("direct")) {
                throw new IllegalArgumentException("Expected direct or preload");
            }
            al.getMethod("create").invoke(null);
            System.out.println("AL.create=PASS");
            // The native library can load successfully while a separate query
            // decodes an ACP device label with the JVM default UTF-8 charset.
            // Do not turn that optional diagnostic failure into a load FAIL.
            try {
                Class<?> alc = Class.forName("org.lwjgl.openal.ALC10", true, loader);
                Class<?> deviceType = Class.forName("org.lwjgl.openal.ALCdevice", false, loader);
                Object device = al.getMethod("getDevice").invoke(null);
                System.out.println("device=" + alc.getMethod("alcGetString", deviceType, int.class).invoke(null, device, 0x1013));
                System.out.println("deviceQuery=PASS");
            } catch (Throwable error) {
                while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
                System.out.println("deviceQuery=FAIL; " + error);
            }
            System.out.println("audioPlayed=false");
        } catch (Throwable error) {
            while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
            code = 1;
            System.out.println("AL.create=FAIL; " + error);
            error.printStackTrace(System.err);
        } finally {
            if (Boolean.TRUE.equals(al.getMethod("isCreated").invoke(null))) al.getMethod("destroy").invoke(null);
            if (child != null) child.close();
        }
        if (code != 0) System.exit(code);
    }
}
