import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Read-only one-shot audio snapshot, dispatched on the existing client queue. */
public final class InspectLegacyAudio {
    public static void main(String[] args) throws Exception {
        VirtualMachine vm=VirtualMachine.attach(args[0]);
        try { vm.loadAgent(args[1],args[2]); } finally { vm.detach(); }
    }
    public static void agentmain(final String output, Instrumentation instrumentation) throws Exception {
        for(Class<?> type:instrumentation.getAllLoadedClasses()) {
            if(!type.getName().equals("link.e4steam.legacy164.Legacy164Client"))continue;
            final ClassLoader loader=type.getClassLoader();
            type.getMethod("submit",Runnable.class).invoke(null,new Runnable(){public void run(){
                try { snapshot(loader,Paths.get(output)); }
                catch(Throwable error){try{Files.writeString(Paths.get(output),"ERROR: "+error,StandardCharsets.UTF_8);}catch(Exception ignored){}}
            }});
            return;
        }
        throw new IllegalStateException("Legacy client tick queue is not loaded");
    }
    static Object field(Object owner,String name)throws Exception {
        for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass()) {
            try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(NoSuchFieldException ignored){}
        }
        throw new NoSuchFieldException(name);
    }
    static void snapshot(ClassLoader loader,Path output)throws Exception {
        StringBuilder text=new StringBuilder("READ ONLY; no transformers, sound playback, reloads or settings changes\n");
        Class<?> mcType=Class.forName("net.minecraft.class_1600",false,loader);
        Object mc=mcType.getDeclaredMethod("method_2965").invoke(null);
        for(Field f:mcType.getDeclaredFields())if(f.getType()==java.io.File.class){
            f.setAccessible(true);text.append("CLIENT_FILE ").append(f.getName()).append('=').append(f.get(mc)).append('\n');
        }
        Object settings=field(mc,"field_3823");
        text.append("VOLUME sound=").append(field(settings,"field_971")).append(" music=").append(field(settings,"field_960")).append('\n');
        Object manager=field(mc,"field_3759");
        Class<?> accessor=Class.forName("com.itlesports.nightmaremode.mixin.interfaces.SoundManagerAccess",false,loader);
        Object system=accessor.getMethod("getSoundSystem").invoke(manager);
        Object backend=system==null?null:field(system,"soundLibrary");
        text.append("BACKEND ").append(backend==null?null:backend.getClass().getName()).append('\n');
        for(Method method:accessor.getMethods())if(method.getParameterCount()==0&&method.getName().startsWith("getSoundPool")){
            Object pool=method.invoke(manager);
            for(Field f:pool.getClass().getDeclaredFields())if(Map.class.isAssignableFrom(f.getType())){
                f.setAccessible(true);Map<?,?> map=(Map<?,?>)f.get(pool);
                text.append("POOL ").append(method.getName()).append(' ').append(f.getName()).append(" keys=").append(map.size()).append('\n');
                for(String key:List.of("step.grass","step.stone","step.sand","dig.grass","dig.stone","dig.sand","ambient.weather.rain","mob.zombie.say","random.click","music")){
                    Object entries=map.get(key);text.append("ENTRY ").append(key).append('=').append(entries).append('\n');
                    if(entries instanceof List<?> && !((List<?>)entries).isEmpty()){
                        Object entry=((List<?>)entries).get(0);
                        java.net.URL url=(java.net.URL)entry.getClass().getMethod("method_5984").invoke(entry);
                        text.append("URL ").append(key).append('=').append(url);
                        try(java.io.InputStream input=url.openStream()){text.append(" readable=").append(input.read()>=0);}
                        catch(Exception error){text.append(" ERROR=").append(error);}
                        text.append('\n');
                    }
                }
            }
        }
        Class<?> al=Class.forName("org.lwjgl.openal.AL",false,loader);
        boolean created=Boolean.TRUE.equals(al.getMethod("isCreated").invoke(null));
        text.append("AL_CREATED ").append(created).append('\n');
        if(created){
            Object device=al.getMethod("getDevice").invoke(null);
            Class<?> deviceType=Class.forName("org.lwjgl.openal.ALCdevice",false,loader);
            Class<?> alc=Class.forName("org.lwjgl.openal.ALC10",false,loader);
            Method getString=alc.getMethod("alcGetString",deviceType,int.class);
            for(int parameter:new int[]{0x1004,0x1005,0x1012,0x1013}){
                text.append("ALC_DEFAULT ").append(Integer.toHexString(parameter)).append('=').append(getString.invoke(null,null,parameter)).append('\n');
                if(parameter!=0x1004&&parameter!=0x1012)text.append("ALC_DEVICE ").append(Integer.toHexString(parameter)).append('=').append(getString.invoke(null,device,parameter)).append('\n');
            }
        }
        Files.writeString(output,text,StandardCharsets.UTF_8);
    }
}
