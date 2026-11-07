import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** One-shot read-only snapshot. No transformers, commands or world writes. */
public final class InspectLegacyClient {
    public static void main(String[] args) throws Exception {
        VirtualMachine vm=VirtualMachine.attach(args[0]);
        try { vm.loadAgent(args[1],args[2]); } finally { vm.detach(); }
    }
    public static void agentmain(final String output, Instrumentation instrumentation) throws Exception {
        for (final Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (!type.getName().equals("link.e4steam.legacy164.Legacy164Client")) continue;
            type.getMethod("submit",Runnable.class).invoke(null,new Runnable() {
                public void run() {
                    try { snapshot(type.getClassLoader(),Paths.get(output)); }
                    catch (Throwable error) {
                        try { Files.write(Paths.get(output),("ERROR: "+error).getBytes(StandardCharsets.UTF_8)); }
                        catch (Exception ignored) { }
                    }
                }
            });
            return;
        }
        throw new IllegalStateException("Legacy client tick queue is not loaded");
    }
    static Object field(Object owner,String name) throws Exception {
        for (Class<?> type=owner.getClass();type!=null;type=type.getSuperclass()) {
            try { Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(owner); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    static String label(Object block) throws Exception {
        StringBuilder text=new StringBuilder(block.getClass().getName());
        for (Class<?> type=block.getClass();type!=null;type=type.getSuperclass())
            for (Field f:type.getDeclaredFields()) if(f.getType()==String.class&&!Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);Object value=f.get(block);if(value!=null)text.append(' ').append(value);
            }
        return text.toString();
    }
    static void snapshot(ClassLoader loader,Path output) throws Exception {
        Class<?> mcType=Class.forName("net.minecraft.class_1600",false,loader);
        Object mc=mcType.getDeclaredMethod("method_2965").invoke(null);
        Class<?> worldType=Class.forName("net.minecraft.class_1150",false,loader);
        Object world=null;
        for(Field f:mcType.getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())) {
            f.setAccessible(true);Object value=f.get(mc);if(value!=null&&worldType.isInstance(value))world=value;
        }
        if(world==null)throw new IllegalStateException("No loaded client world");
        Method getBlock=worldType.getMethod("method_3774",int.class,int.class,int.class);
        Field rf=Class.forName("net.minecraft.class_197",false,loader).getDeclaredField("field_492");rf.setAccessible(true);
        Object[] registry=(Object[])rf.get(null);
        Set<Integer> relevant=new HashSet<Integer>(Arrays.asList(7,49,51,90));
        StringBuilder text=new StringBuilder("READ ONLY; actual client tick; no world changes\n");
        Class<?> create=Class.forName("net.minecraft.class_363",false,loader);
        for(Field f:create.getDeclaredFields())if(f.getName().toLowerCase(Locale.ROOT).contains("difficulty"))
            text.append("CREATE FIELD ").append(f.getName()).append(' ').append(f.getType().getName()).append('\n');
        try {
            Object settings=field(mc,"field_3823");
            text.append("AUDIO soundVolume=").append(field(settings,"field_971")).append(" musicVolume=").append(field(settings,"field_960")).append('\n');
            Object manager=field(mc,"field_3759");
            Class<?> accessor=Class.forName("com.itlesports.nightmaremode.mixin.interfaces.SoundManagerAccess",false,loader);
            Object system=accessor.getMethod("getSoundSystem").invoke(manager);
            text.append("AUDIO library=").append(system.getClass().getMethod("getLibraryType").invoke(system));
            text.append(" masterVolume=").append(system.getClass().getMethod("getMasterVolume").invoke(system)).append('\n');
            Object pool=accessor.getMethod("getSoundPoolSounds").invoke(manager);
            for(Field f:pool.getClass().getDeclaredFields())if(Map.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);Map<?,?> entries=(Map<?,?>)f.get(pool);
                text.append("AUDIO pool=").append(f.getName()).append(" size=").append(entries.size()).append(" click=").append(entries.get("random.click")).append('\n');
                Object click=entries.get("random.click");
                if(click instanceof List<?> && !((List<?>)click).isEmpty()) {
                    Object entry=((List<?>)click).get(0);
                    text.append("AUDIO clickURL=").append(entry.getClass().getMethod("method_5984").invoke(entry)).append('\n');
                }
            }
            Class<?> al=Class.forName("org.lwjgl.openal.AL",false,loader);
            text.append("AUDIO ALcreated=").append(al.getMethod("isCreated").invoke(null)).append('\n');
            Object device=al.getMethod("getDevice").invoke(null);
            Class<?> deviceType=Class.forName("org.lwjgl.openal.ALCdevice",false,loader);
            Class<?> alc=Class.forName("org.lwjgl.openal.ALC10",false,loader);
            text.append("AUDIO device=").append(alc.getMethod("alcGetString",deviceType,int.class).invoke(null,device,0x1005)).append('\n');
            if(Boolean.TRUE.equals(alc.getMethod("alcIsExtensionPresent",deviceType,String.class).invoke(null,device,"ALC_EXT_disconnect"))) {
                java.nio.IntBuffer connected=java.nio.ByteBuffer.allocateDirect(4).order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
                alc.getMethod("alcGetInteger",deviceType,int.class,java.nio.IntBuffer.class).invoke(null,device,0x313,connected);
                text.append("AUDIO connected=").append(connected.get(0)).append('\n');
            }
        } catch(Throwable error) { text.append("AUDIO inspection failed: ").append(error).append('\n'); }
        for(int id=0;id<registry.length;id++)if(registry[id]!=null) {
            String name=label(registry[id]);
            if(name.toLowerCase(Locale.ROOT).matches(".*(obsidian|portal|campfire|firestarter|flintandsteel).*")) {
                relevant.add(id);text.append("BLOCK ").append(id).append(' ').append(name).append('\n');
            }
        }
        Set<String> visited=new HashSet<String>();Map<Integer,Integer> counts=new TreeMap<Integer,Integer>();
        for(Object player:(List<?>)field(world,"field_4544")) {
            int px=(int)Math.floor(((Number)field(player,"field_3252")).doubleValue());
            int py=(int)Math.floor(((Number)field(player,"field_3253")).doubleValue());
            int pz=(int)Math.floor(((Number)field(player,"field_3254")).doubleValue());
            text.append("PLAYER position=").append(px).append(',').append(py).append(',').append(pz).append('\n');
            for(int x=px-24;x<=px+24;x++)for(int y=Math.max(0,py-12);y<=Math.min(255,py+12);y++)for(int z=pz-24;z<=pz+24;z++) {
                String point=x+","+y+","+z;if(!visited.add(point))continue;
                int id=((Number)getBlock.invoke(world,x,y,z)).intValue();if(id==0)continue;
                counts.put(id,counts.containsKey(id)?counts.get(id)+1:1);
                if(relevant.contains(id))text.append("AT ").append(point).append(" id=").append(id).append('\n');
            }
        }
        text.append("COUNTS ").append(counts).append('\n');
        Files.write(output,text.toString().getBytes(StandardCharsets.UTF_8));
    }
}
