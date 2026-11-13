import java.io.*;
import java.lang.instrument.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.time.Instant;
import java.util.*;
import org.objectweb.asm.*;

/** QA only. Dispatches native UI lifecycle requests on the real client thread. */
public final class BtwQaAgent implements ClassFileTransformer {
    private static Path fixture, game, evidence;
    private static String world, mode;
    private static java.awt.Frame hiddenParent;
    private static boolean lanModeProbe, lanModeObserved;
    private static boolean survivalCommandsProbe;
    private static boolean survivalCommandsAllowed;
    private static int lanProbeCalls;
    private static String lanProbeMode;
    private static boolean lanProbeCommands;
    private static String lanProbeChat;
    private static String sharingCommand;
    private static boolean busy, started, joined, leaving, ended, failed, published, publicationObserved, skinObserved, atlasObserved;
    private static int menuTicks, worldTicks, retryTicks;
    private static long audioReadyAt, audioStartedAt;
    private static int audioSoundIndex;
    private static boolean audioMusicStarted;
    private static float audioOriginalMusic=-1;
    private static long joinedAt;
    private static Object discoveryParent, discoveryScreen, oldSkinTexture;
    private static int discoveryTicks, dynamicStage, dynamicStarted, cleanupTicks;
    private static boolean discoveryObserved, rawTcpStarted;
    private static byte[] originalSkinRegistry;
    private static Path skinRegistry;
    private static volatile String rawTcpResult;
    private static java.net.ServerSocket publicSocket;
    private static boolean inventoryOpened, inventoryObserved;
    private static int inventoryStarted;
    private static Object inventoryScreen;
    private static long renderSpecialsCalls, renderCallsBeforeInventory;
    private interface ServerAction { void run(Object server) throws Exception; }
    private static final java.util.concurrent.ConcurrentLinkedQueue<ServerAction> SERVER_ACTIONS=new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static boolean progressQueued;
    private static volatile boolean progressDone;
    private static volatile Throwable progressFailure;
    private static boolean capturingRowText;
    private static final Set<String> ROW_TEXT=new LinkedHashSet<>();
    private static volatile String lobbyMetadataResult;
    private static boolean lobbyMetadataStarted;
    private static Path nameRegistry;
    private static byte[] originalNameRegistry;
    private static int nameStage, namePhase, nameStarted;
    private static long nameEarsBefore,nameFlipsBefore;
    private static long earCalls, upsideDownCalls;
    private static boolean nameMechanicsDone;
    private static volatile Integer appleResult;
    private static volatile Throwable appleFailure;
    private static final String[] NAME_CASES={"BtwQaControl","Notch","Dinnerbone","Grumm","deadmau5"};

    public static void premain(String ignored, Instrumentation inst) throws Exception {
        fixture=Path.of(required("root")).toRealPath();
        if(!fixture.toString().toLowerCase(Locale.ROOT).contains(File.separator+"artifacts"+File.separator) ||
           !Files.isRegularFile(fixture.resolve(".btw-runtime-qa.json")))throw new SecurityException("Unmarked QA fixture");
        game=Path.of(required("game")).toRealPath();evidence=Path.of(required("evidence")).toAbsolutePath().normalize();
        if(!game.startsWith(fixture)||!evidence.startsWith(fixture))throw new SecurityException("QA path escape");
        world=required("world");mode=required("mode");
        if(!world.matches("BtwQa[A-Za-z0-9_-]{0,40}") || !(mode.equals("fresh")||mode.equals("reenter")))throw new SecurityException("Invalid native action");
        lanModeProbe = Boolean.getBoolean("btw.qa.lanGuestMode");
        String survivalCommands = System.getProperty("btw.qa.survivalCommands", "");
        survivalCommandsProbe = !survivalCommands.isEmpty();
        if (survivalCommandsProbe && !Set.of("true", "false").contains(survivalCommands))
            throw new IllegalArgumentException("Expected true or false for the Survival commands probe");
        survivalCommandsAllowed = Boolean.parseBoolean(survivalCommands);
        if ((lanModeProbe || survivalCommandsProbe) && Boolean.getBoolean("btw.qa.publish")
                || lanModeProbe && survivalCommandsProbe)
            throw new SecurityException("Guest mode probe requires isolated publication");
        Path save=game.resolve("saves").resolve(world);
        if(mode.equals("fresh")&&Files.exists(save))throw new SecurityException("Fresh world exists");
        if(mode.equals("reenter")&&!Files.isRegularFile(save.resolve("level.dat")))throw new SecurityException("Reentry world missing");
        Files.createDirectories(evidence);event("agent_ready","qaOnlyNativeDispatchAndRenderObservation=true");
        inst.addTransformer(new BtwQaAgent());
    }

    public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes) {
        boolean hiddenDisplay = Boolean.getBoolean("btw.qa.hiddenWindow") && name.equals("org/lwjgl/opengl/Display");
        boolean render=name.equals("net/minecraft/class_570");
        boolean server=name.equals("net/minecraft/server/MinecraftServer");
        boolean font=name.equals("net/minecraft/class_370");
        boolean living=name.equals("net/minecraft/class_1635");
        boolean isolatedShare = lanModeProbe && name.equals("net/minecraft/class_601");
        boolean isolatedSteam = (lanModeProbe || survivalCommandsProbe) && name.equals("link/e4steam/retro/RetroBootstrap");
        boolean observeChat = (lanModeProbe || survivalCommandsProbe) && name.equals("net/minecraft/class_357");
        if(!name.equals("net/minecraft/class_1600")&&!render&&!server&&!font&&!living
                &&!isolatedShare&&!isolatedSteam&&!observeChat&&!hiddenDisplay)return null;
        try {
            ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
            final int[] count={0};
            reader.accept(new ClassVisitor(Opcodes.ASM9,writer){
                public MethodVisitor visitMethod(int access,String n,String descriptor,String sig,String[] exceptions){
                    MethodVisitor target=super.visitMethod(access,n,descriptor,sig,exceptions);
                    if (hiddenDisplay && n.equals("create") && descriptor.equals("(Lorg/lwjgl/opengl/PixelFormat;Lorg/lwjgl/opengl/Drawable;Lorg/lwjgl/opengl/ContextAttribs;)V")) {
                        count[0]++;
                        return new MethodVisitor(Opcodes.ASM9,target) {
                            public void visitCode() {
                                super.visitCode();super.visitLdcInsn(org.objectweb.asm.Type.getObjectType(name));
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,"BtwQaAgent","prepareHiddenDisplay","(Ljava/lang/Class;)V",false);
                            }
                        };
                    }
                    if (isolatedShare && n.equals("method_3000")
                            && descriptor.equals("(Lnet/minecraft/class_1157;Z)Ljava/lang/String;")) {
                        // Observe the real native screen's transformed arguments without opening
                        // an unauthenticated TCP listener or broadcasting a fixture on the LAN.
                        count[0]++;
                        target.visitCode();
                        target.visitVarInsn(Opcodes.ALOAD, 1);
                        target.visitVarInsn(Opcodes.ILOAD, 2);
                        target.visitMethodInsn(Opcodes.INVOKESTATIC, "BtwQaAgent", "observeLanShare",
                            "(Ljava/lang/Object;Z)Ljava/lang/String;", false);
                        target.visitInsn(Opcodes.ARETURN);
                        target.visitMaxs(0, 0);
                        target.visitEnd();
                        return null;
                    }
                    if (isolatedSteam && n.equals("install")
                            && descriptor.equals("(Ljava/lang/String;Llink/e4steam/retro/RetroPlatform;)V")) {
                        // Load the exact transport's mixins without opening the real Steam account.
                        count[0]++;
                        target.visitCode();
                        target.visitInsn(Opcodes.RETURN);
                        target.visitMaxs(0, 0);
                        target.visitEnd();
                        return null;
                    }
                    if (isolatedSteam && n.equals("handleClientCommand") && descriptor.equals("(Ljava/lang/String;)Z")) {
                        count[0]++;
                        target.visitCode();target.visitVarInsn(Opcodes.ALOAD,0);
                        target.visitMethodInsn(Opcodes.INVOKESTATIC,"BtwQaAgent","observeSharingCommand","(Ljava/lang/String;)Z",false);
                        target.visitInsn(Opcodes.IRETURN);target.visitMaxs(0,0);target.visitEnd();return null;
                    }
                    if (observeChat && n.equals("method_898") && descriptor.equals("(Ljava/lang/String;)V")) {
                        count[0]++;
                        return new MethodVisitor(Opcodes.ASM9, target) {
                            public void visitCode() {
                                super.visitCode();
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "BtwQaAgent", "observeShareChat", "(Ljava/lang/String;)V", false);
                            }
                        };
                    }
                    if(!render&&!server&&!font&&!living&&n.equals("method_2954")&&descriptor.equals("()V")) {
                        count[0]++;
                        return new MethodVisitor(Opcodes.ASM9,target){public void visitCode(){super.visitCode();super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"BtwQaAgent","tick","(Ljava/lang/Object;)V",false);}};
                    }
                    if(font&&(n.equals("method_956")||n.equals("method_4246")||n.equals("method_4247"))&&descriptor.startsWith("(Ljava/lang/String;")) {
                        count[0]++;return new MethodVisitor(Opcodes.ASM9,target){public void visitCode(){super.visitCode();super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,"BtwQaAgent","fontText","(Ljava/lang/String;)V",false);}};
                    }
                    if(server&&n.equals("method_3044")&&descriptor.equals("()V")) {
                        count[0]++;
                        return new MethodVisitor(Opcodes.ASM9,target){public void visitCode(){super.visitCode();super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"BtwQaAgent","serverTick","(Ljava/lang/Object;)V",false);}};
                    }
                    if(render&&n.equals("method_1582")&&descriptor.equals("(Lnet/minecraft/class_1631;F)V")) {
                        count[0]++;
                        return new MethodVisitor(Opcodes.ASM9,target){public void visitCode(){super.visitCode();super.visitMethodInsn(Opcodes.INVOKESTATIC,"BtwQaAgent","renderSpecialsObserved","()V",false);}
                            public void visitMethodInsn(int opcode,String owner,String method,String descriptor,boolean itf){if(owner.equals("net/minecraft/class_440")&&method.equals("method_1170")&&descriptor.equals("(F)V"))super.visitMethodInsn(Opcodes.INVOKESTATIC,"BtwQaAgent","earsObserved","()V",false);super.visitMethodInsn(opcode,owner,method,descriptor,itf);}};
                    }
                    if(living&&n.equals("method_5777")&&descriptor.equals("(Lnet/minecraft/class_1699;FFF)V")) {
                        count[0]++;return new MethodVisitor(Opcodes.ASM9,target){public void visitMethodInsn(int opcode,String owner,String n,String d,boolean itf){if(opcode==Opcodes.INVOKESTATIC&&owner.equals("org/lwjgl/opengl/GL11")&&n.equals("glRotatef")&&d.equals("(FFFF)V"))super.visitMethodInsn(Opcodes.INVOKESTATIC,"BtwQaAgent","forwardRotate","(FFFF)V",false);else super.visitMethodInsn(opcode,owner,n,d,itf);}};
                    }
                    return target;
                }
            },0);
            if(count[0]!=(font?3:isolatedSteam?2:1))throw new IllegalStateException("Wrong native QA target count "+count[0]);
            if (isolatedShare) event("native_guest_mode_observer_bound", "target=class_601.method_3000;networkSuppressedByQa=true");
            else if (isolatedSteam) event("native_steam_disabled_in_fixture", "transportMixinsLoaded=true;steamBootstrapSuppressedByQa=true");
            else if (observeChat) event("native_share_chat_observer_bound", "target=class_357.method_898");
            else event(living?"native_rotation_observer_bound":font?"native_font_observer_bound":server?"native_server_dispatch_bound":render?"native_render_observer_bound":"native_tick_bound",living?"target=net.minecraft.class_1635.method_5777":font?"targets=drawStringWithShadow/drawString":server?"target=MinecraftServer.method_3044":render?"target=net.minecraft.class_570.method_1582":"target=net.minecraft.class_1600.method_2954()V");
            return writer.toByteArray();
        } catch(Throwable t){failure(t);throw new IllegalStateException(t);}
    }
    public static void renderSpecialsObserved(){renderSpecialsCalls++;}
    public static void prepareHiddenDisplay(Class<?> display) {
        if (hiddenParent != null) return;
        try {
            int width=(Integer)display.getMethod("getWidth").invoke(null);
            int height=(Integer)display.getMethod("getHeight").invoke(null);
            hiddenParent=new java.awt.Frame();hiddenParent.setUndecorated(true);hiddenParent.setFocusableWindowState(false);
            java.awt.Canvas canvas=new java.awt.Canvas();canvas.setSize(width,height);hiddenParent.add(canvas);
            hiddenParent.setSize(width,height);hiddenParent.addNotify();hiddenParent.validate();
            display.getMethod("setParent",java.awt.Canvas.class).invoke(null,canvas);
            event("native_hidden_display","unshownNonFocusableParent=true");
        } catch(Exception failure) { throw new IllegalStateException(failure); }
    }
    public static String observeLanShare(Object mode, boolean commands) {
        lanProbeCalls++;
        lanProbeMode = ((Enum<?>)mode).name();
        lanProbeCommands = commands;
        return "25565";
    }
    public static void observeShareChat(String line) { lanProbeChat = line; }
    public static boolean observeSharingCommand(String command) { sharingCommand=command;return true; }
    public static void fontText(String text){if(capturingRowText&&text!=null)ROW_TEXT.add(text);}
    public static void earsObserved(){earCalls++;}
    public static void forwardRotate(float angle,float x,float y,float z){if(angle==180F&&x==0F&&y==0F&&z==1F)upsideDownCalls++;try{Class.forName("org.lwjgl.opengl.GL11",true,Thread.currentThread().getContextClassLoader()).getMethod("glRotatef",float.class,float.class,float.class,float.class).invoke(null,angle,x,y,z);}catch(Exception failure){throw new IllegalStateException(failure);}}
    public static void serverTick(Object server){ServerAction task;while((task=SERVER_ACTIONS.poll())!=null)try{task.run(server);progressDone=true;}catch(Throwable error){progressFailure=error;}}

    public static void tick(Object client) {
        if(busy||ended)return;busy=true;
        ClassLoader before=Thread.currentThread().getContextClassLoader();
        try {
            ClassLoader cl=client.getClass().getClassLoader();Thread.currentThread().setContextClassLoader(cl);
            if(!((File)field(client.getClass(),"field_3762").get(client)).toPath().toRealPath().equals(game))throw new SecurityException("Wrong game path");
            Object screen=field(client.getClass(),"field_3816").get(client);
            Object player=field(client.getClass(),"field_3805").get(client);
            Object actualWorld=field(client.getClass(),"field_3803").get(client);
            if(failed) {
                if(!Files.isRegularFile(evidence.resolve("stop.flag")))return;
                if(actualWorld==null){ended=true;method(client.getClass(),"method_2949").invoke(client);event("failed_probe_normal_shutdown","normalGameShutdown=true");return;}
                if(retryTicks-->0)return;
                Object pause=Class.forName("net.minecraft.class_385",true,cl).getConstructor().newInstance();
                method(client.getClass(),"method_2928",Class.forName("net.minecraft.class_388",false,cl)).invoke(client,pause);
                if(click(pause,1,false))event("failed_probe_native_quit_requested","authorPauseMenuGateRespected=true");
                else{method(client.getClass(),"method_2928",Class.forName("net.minecraft.class_388",false,cl)).invoke(client,new Object[]{null});retryTicks=100;}
                return;
            }
            if(Boolean.getBoolean("btw.qa.audioProbe")) {
                if(actualWorld!=null)throw new SecurityException("Audio-only probe must never open a world");
                if(screen!=null&&screen.getClass().getName().equals("net.minecraft.class_624"))audioProbe(client,cl);
                return;
            }
            if(discoveryScreen!=null) {
                if(++discoveryTicks<40)return;
                if(screen!=discoveryScreen)throw new IllegalStateException("Native multiplayer screen unexpectedly changed");
                Class<?> discovery=Class.forName("link.e4steam.legacy164.LegacyDiscovery",true,cl);
                Map<?,?> polls=(Map<?,?>)field(discovery,"POLLS").get(null);
                if(polls.size()!=1)throw new IllegalStateException("Native discovery lease was not created");
                Object poll=polls.get(discoveryScreen);
                method(poll.getClass(),"apply",Map.class).invoke(poll,Map.of());
                if(field(discoveryScreen.getClass(),"field_1197").get(discoveryScreen)==null)throw new IllegalStateException("Native transient server list was not initialized");
                syntheticNativeSteamRow(discoveryScreen,poll,cl);
                Object stale=Class.forName("net.minecraft.class_485",true,cl).getConstructor(String.class,String.class).newInstance("Private stale QA entry","e4steam-legacy-1.invalid");
                method(discoveryScreen.getClass(),"method_1003",stale.getClass()).invoke(discoveryScreen,stale);
                if(field(client.getClass(),"field_3816").get(client)!=discoveryScreen)throw new IllegalStateException("Stale Steam entry reached native TCP/DNS connection");
                method(client.getClass(),"method_2928",Class.forName("net.minecraft.class_388",false,cl)).invoke(client,discoveryParent);
                if(!polls.isEmpty())throw new IllegalStateException("Native discovery lease survived screen close");
                discoveryScreen=null;discoveryObserved=true;menuTicks=0;
                event("native_multiplayer_lifecycle_observed","nativeGuiOpened=true;emptyRowsApplied=true;staleNativeJoinSuppressed=true;leaseCreated=true;nativeOnCloseRan=true;leaseRemoved=true;livePeerRowPass=false");return;
            }
            if(!started && actualWorld==null && screen!=null && screen.getClass().getName().equals("net.minecraft.class_624")) {
                if(++menuTicks<40)return;
                if(Boolean.getBoolean("btw.qa.discovery")&&!discoveryObserved) {
                    discoveryParent=screen;discoveryScreen=Class.forName("net.minecraft.class_376",true,cl).getConstructor(Class.forName("net.minecraft.class_388",false,cl)).newInstance(screen);
                    method(client.getClass(),"method_2928",Class.forName("net.minecraft.class_388",false,cl)).invoke(client,discoveryScreen);return;
                }
                started=true;recordMods(cl);
                event("native_title_menu","screen="+screen.getClass().getName());
                if(mode.equals("fresh")) {
                    Class<?> gui=Class.forName("net.minecraft.class_388",false,cl);
                    Object create=Class.forName("net.minecraft.class_363",true,cl).getConstructor(gui).newInstance(screen);
                    method(client.getClass(),"method_2928",gui).invoke(client,create);
                    Object name=field(create.getClass(),"field_1094").get(create);
                    method(name.getClass(),"method_922",String.class).invoke(name,world);
                    method(create.getClass(),"method_914").invoke(create);
                    if(!world.equals(field(create.getClass(),"field_1096").get(create)))throw new SecurityException("Native save-folder mismatch");
                    method(create.getClass(),"method_1033").invoke(create);
                    if (lanModeProbe) {
                        Field difficulty = field(create.getClass(), "difficultyID");
                        Object difficultyButton = field(create.getClass(), "buttonDifficultyLevel").get(create);
                        if (difficulty.getInt(create) != 2 || field(difficultyButton.getClass(), "field_1055").getBoolean(difficultyButton))
                            throw new IllegalStateException("New worlds must offer only the selected Nightmare difficulty");
                        Object beforeMode = field(create.getClass(), "field_1097").get(create);
                        method(create.getClass(), "method_1027", Class.forName("net.minecraft.class_356",false,cl)).invoke(create,difficultyButton);
                        if (difficulty.getInt(create) != 2 || !beforeMode.equals(field(create.getClass(), "field_1097").get(create)))
                            throw new IllegalStateException("Difficulty action escaped the single choice or changed game mode");
                        event("native_single_difficulty_verified", "difficultyIndex=2;selectorEnabled=false;gameModePreserved=true");
                    }
                    if (lanModeProbe) {
                        Field selectedMode = field(create.getClass(), "field_1097");
                        for (int attempt = 0; attempt < 3 && !"creative".equals(selectedMode.get(create)); attempt++)
                            click(create, 2, true);
                        if (!"creative".equals(selectedMode.get(create)))
                            throw new IllegalStateException("The author create-world menu did not select Creative");
                    }
                    click(create,0,true);
                    event("native_create_requested", lanModeProbe ? "creativeSelectedThroughNativeGui=true" : "authorGuiDefaultsPreserved=true");
                } else {
                    Class<?> settings=Class.forName("net.minecraft.class_1156",false,cl);
                    method(client.getClass(),"method_2935",String.class,String.class,settings).invoke(client,world,world,null);
                    event("native_reenter_requested","existingClosedQaWorld=true");
                }
            }
            if(actualWorld!=null && player!=null) {
                if(!joined) {
                    joined=true;joinedAt=System.nanoTime();
                    int max=((Number)Class.forName("org.lwjgl.opengl.GL11",true,cl).getMethod("glGetInteger",int.class).invoke(null,3379)).intValue();
                    event("native_world_joined","player="+player.getClass().getName()+";GL_MAX_TEXTURE_SIZE="+max);
                }
                worldTicks++;
                if(!atlasObserved && worldTicks>=40){recordAtlases(client,cl);atlasObserved=true;}
                if (lanModeProbe && !lanModeObserved && worldTicks >= 80) {
                    verifyGuestMode(client, cl);
                    verifyAchievementMixins(cl);
                    verifySharingChat(client, cl);
                    verifyNativeAudio(client, cl);
                    lanModeObserved = true;
                }
                if (survivalCommandsProbe && !progressQueued && worldTicks >= 80) {
                    openSurvivalWorld(client, cl);
                    progressQueued = true;
                    SERVER_ACTIONS.add(BtwQaAgent::checkSurvivalCommands);
                }
                if(!skinObserved && worldTicks>=60 && !System.getProperty("minecraft.portable.skin.registry","").isBlank()) {
                    String nativeName=(String)field(player.getClass(),"field_3982").get(player);
                    Class<?> profiles=Class.forName("api.client.mojapi.ProfileUtils",true,cl);
                    Object profile=profiles.getMethod("getUserProfile",String.class,boolean.class).invoke(null,nativeName,false);
                    if(profile instanceof Optional<?>)profile=((Optional<?>)profile).orElseThrow(()->new IllegalStateException("BTW profile missing"));
                    boolean slim=(Boolean)profile.getClass().getMethod("isSlim").invoke(profile);
                    String url=(String)profile.getClass().getMethod("getSkinUrl").invoke(profile);
                    Object uuid=profile.getClass().getMethod("getUuid").invoke(profile);
                    String expectedUuid=System.getProperty("minecraft.portable.identity.localPlayer","");
                    if(!String.valueOf(uuid).equalsIgnoreCase(expectedUuid)||!url.startsWith("http://127.0.0.1:") || slim!=System.getProperty("btw.qa.expectedSkinModel","classic").equals("slim"))throw new IllegalStateException("Selected profile identity/skin did not reach BTW API");
                    Object texture=method(player.getClass(),"method_5723").invoke(player);
                    java.awt.image.BufferedImage image=(java.awt.image.BufferedImage)field(texture.getClass(),"field_6550").get(texture);
                    if(image!=null) {
                        int textureId=((Number)method(texture.getClass(),"method_5855").invoke(texture)).intValue();
                        String title=(String)Class.forName("org.lwjgl.opengl.Display",true,cl).getMethod("getTitle").invoke(null);
                        boolean titleMatch=title.equals(System.getProperty("minecraft.portable.identity.windowTitleText",""));
                        int compared=checkSelectedPixels(image);
                        event("selected_skin_native_observed","uuidMatches=true;loopbackSource=true;slim="+slim+";image="+image.getWidth()+"x"+image.getHeight()+";selectedOpaquePixelsMatched="+compared+";gpuTextureAllocated="+(textureId>0)+";windowTitleMatches="+titleMatch+";visualPassClaimed=false");
                        if(image.getWidth()!=64||image.getHeight()!=64||textureId<=0||!titleMatch)throw new IllegalStateException("Native selected skin dimensions/texture/title mismatch");
                        skinObserved=true;
                    }
                }
                if(Boolean.getBoolean("btw.qa.dynamicSkin")&&skinObserved)dynamicSkin(player,cl);
                if(Boolean.getBoolean("btw.qa.inventoryRender")&&skinObserved&&!inventoryObserved) {
                    if(!inventoryOpened) {
                        inventoryScreen=Class.forName("net.minecraft.class_422",true,cl).getConstructor(Class.forName("net.minecraft.class_988",false,cl)).newInstance(player);
                        renderCallsBeforeInventory=renderSpecialsCalls;inventoryStarted=worldTicks;inventoryOpened=true;
                        method(client.getClass(),"method_2928",Class.forName("net.minecraft.class_388",false,cl)).invoke(client,inventoryScreen);event("native_inventory_render_requested","nativeInventoryGui=true");return;
                    }
                    if(worldTicks-inventoryStarted<30)return;
                    if(renderSpecialsCalls<=renderCallsBeforeInventory)throw new IllegalStateException("Native inventory player/cape renderer was not exercised");
                    method(client.getClass(),"method_2928",Class.forName("net.minecraft.class_388",false,cl)).invoke(client,new Object[]{null});inventoryObserved=true;
                    event("native_inventory_render_observed","renderSpecialsCalls="+(renderSpecialsCalls-renderCallsBeforeInventory)+";selectedNoCapePathRendered=true;visualPassClaimed=false");
                }
                if(Boolean.getBoolean("btw.qa.publish") && !published && worldTicks>=80) {
                    Object pause=Class.forName("net.minecraft.class_385",true,cl).getConstructor().newInstance();
                    method(client.getClass(),"method_2928",Class.forName("net.minecraft.class_388",false,cl)).invoke(client,pause);
                    click(pause,7,true);published=true;event("native_open_to_lan_requested","nativePauseButton=7;shareScreenStartNotPressed=true");
                }
                if(published && !publicationObserved && worldTicks>=120) {
                    Object server=method(client.getClass(),"method_2909").invoke(client);
                    boolean nativePublic=(Boolean)method(server.getClass(),"method_1638").invoke(server);
                    Object current=field(client.getClass(),"field_3816").get(client);
                    if(!nativePublic || current!=null)throw new IllegalStateException("One-click native publication did not return to the game");
                    Object runtime=Class.forName("link.e4steam.steam.SteamRuntime",true,cl).getMethod("get").invoke(null);
                    Object view=runtime.getClass().getMethod("safeSessionView").invoke(runtime);
                    String state=(String)view.getClass().getMethod("stateCode").invoke(view);
                    String title=(String)Class.forName("org.lwjgl.opengl.Display",true,cl).getMethod("getTitle").invoke(null);
                    if(!title.equals(System.getProperty("minecraft.portable.identity.windowTitleText","")))throw new IllegalStateException("Published title changed");
                    Object listener=field(server.getClass(),"field_2206").get(server), thread=field(listener.getClass(),"field_2216").get(listener);
                    for(Field f:thread.getClass().getDeclaredFields())if(f.getType()==java.net.ServerSocket.class){f.setAccessible(true);publicSocket=(java.net.ServerSocket)f.get(thread);}
                    if(publicSocket==null||!publicSocket.getInetAddress().isLoopbackAddress())throw new IllegalStateException("Native server was not loopback-bound");
                    event("native_publication_observed","nativeIsPublic=true;screenIsNull=true;steamState="+state+";loopbackBound=true;titleMatches=true;twoAccountSteamPass=false");publicationObserved=true;
                    if(Boolean.getBoolean("btw.qa.rawTcpNegative"))startRawTcpNegative(publicSocket.getLocalPort(),(String)field(player.getClass(),"field_3982").get(player),cl);
                    if(Boolean.getBoolean("btw.qa.discovery"))startLobbyMetadataObservation(runtime,publicSocket.getLocalPort(),(String)field(player.getClass(),"field_3982").get(player),cl);
                }
                String progress=System.getProperty("btw.qa.progress","");
                if(!progress.isBlank()&&!progressQueued&&worldTicks>=150) {
                    if(!progress.equals("seed")&&!progress.equals("verify"))throw new IllegalStateException("Unknown native progress probe");
                    progressQueued=true;SERVER_ACTIONS.add(s->nativeProgress(s,progress));
                }
                if(progressFailure!=null)throw new IllegalStateException("Native server progress probe failed",progressFailure);
                if(Boolean.getBoolean("btw.qa.nameMechanics")&&worldTicks>=170)nameMechanics(client,player,cl);
                if(worldTicks<200 || leaving)return;
                if(survivalCommandsProbe && !progressDone)return;
                if(Boolean.getBoolean("btw.qa.nameMechanics")&&!nameMechanicsDone){if(worldTicks>1600)throw new IllegalStateException("Native name mechanics probe did not finish");return;}
                if(!progress.isBlank()&&!progressDone){if(worldTicks>800)throw new IllegalStateException("Native server task did not complete");return;}
                if(!System.getProperty("minecraft.portable.skin.registry","").isBlank()&&!skinObserved) {
                    if(worldTicks>600)throw new IllegalStateException("Selected skin image never reached native downloader");return;
                }
                if(Boolean.getBoolean("btw.qa.dynamicSkin")&&dynamicStage!=3){if(worldTicks>800)throw new IllegalStateException("Dynamic skin probe did not finish");return;}
                if(rawTcpStarted) {
                    if(rawTcpResult==null){if(worldTicks>800)throw new IllegalStateException("Raw TCP login did not complete");return;}
                    Object server=method(client.getClass(),"method_2909").invoke(client);
                    int players=((Number)method(server.getClass(),"method_2043").invoke(server)).intValue();
                    if(!rawTcpResult.equals("DENIED")||players!=1)throw new IllegalStateException("Raw TCP spoof was not safely rejected: "+rawTcpResult+";players="+players);
                    event("native_raw_tcp_spoof_rejected","realPacket2Protocol78=true;claimedHostAlias=true;authenticatedBridgeRequired=true;nativePlayerCount=1");rawTcpStarted=false;
                }
                if(lobbyMetadataStarted) {
                    if(lobbyMetadataResult==null){if(worldTicks>2400)throw new IllegalStateException("Own Steam lobby metadata timed out");return;}
                    if(!lobbyMetadataResult.equals("PASS"))throw new IllegalStateException("Own Steam lobby metadata mismatch: "+lobbyMetadataResult);
                    event("native_steam_lobby_metadata_verified","actualOwnLobby=true;currentHostOwnerGeneration=true;actualSteamOwnerMatches=true;lanminecraftDisplayMarker=1;playerMatchesLauncherName=true;worldMatchesNativeWorldInfo=true;twoAccountSteamPass=false");lobbyMetadataStarted=false;
                }
                if(retryTicks-->0)return;
                // Use the actual pause menu and its enabled native quit action.
                Object pause=Class.forName("net.minecraft.class_385",true,cl).getConstructor().newInstance();
                method(client.getClass(),"method_2928",Class.forName("net.minecraft.class_388",false,cl)).invoke(client,pause);
                if(click(pause,1,false)) {
                    leaving=true;event("native_save_quit_requested","authorPauseMenuGateRespected=true;worldTicks="+worldTicks);
                } else {
                    method(client.getClass(),"method_2928",Class.forName("net.minecraft.class_388",false,cl)).invoke(client,new Object[]{null});
                    retryTicks=100;event("native_quit_deferred","authorQuitButtonDisabled=true");
                }
            }
            if(joined && leaving && actualWorld==null && screen!=null && screen.getClass().getName().equals("net.minecraft.class_624")) {
                if(published) {
                    if(++cleanupTicks<40)return;
                    if(publicSocket!=null&&!publicSocket.isClosed())throw new IllegalStateException("Native listener survived world close");
                    Object runtime=Class.forName("link.e4steam.steam.SteamRuntime",true,cl).getMethod("get").invoke(null),view=runtime.getClass().getMethod("safeSessionView").invoke(runtime);
                    String state=(String)view.getClass().getMethod("stateCode").invoke(view);
                    if(state.equals("ACTIVE"))throw new IllegalStateException("Steam hosted session survived world close");
                    Class<?> publication=Class.forName("link.e4steam.legacy164.LegacyPublication",true,cl);
                    boolean publicationEmpty=((Map<?,?>)field(publication,"PUBLICATIONS").get(null)).isEmpty()&&((Map<?,?>)field(publication,"SERVER_TASKS").get(null)).isEmpty();
                    if(!publicationEmpty)throw new IllegalStateException("World description tasks survived native close");
                    event("native_publication_cleanup_observed","nativeListenerClosed=true;steamState="+state+";publicationGenerationAndTasksCleared=true;twoAccountSteamPass=false");
                }
                event("native_saved_returned_to_menu","worldTicks="+worldTicks+";elapsedMillis="+((System.nanoTime()-joinedAt)/1_000_000));
                ended=true;method(client.getClass(),"method_2949").invoke(client);
                event("native_shutdown_requested","normalGameShutdown=true");
            }
        } catch(Throwable t){failure(t);} finally {Thread.currentThread().setContextClassLoader(before);busy=false;}
    }

    private static void openSurvivalWorld(Object client, ClassLoader loader) throws Exception {
        Object server = method(client.getClass(), "method_2909").invoke(client);
        if (mode.equals("fresh")) {
            Object overworld = method(server.getClass(), "method_2991", int.class).invoke(server, 0);
            Object info = method(overworld.getClass(), "method_3588").invoke(overworld);
            // Seed the disposable world's persisted setting because author UI can force its default.
            field(info.getClass(), "field_252").setBoolean(info, survivalCommandsAllowed);
        }
        Object pause = Class.forName("net.minecraft.class_385", true, loader).getConstructor().newInstance();
        Class<?> screen = Class.forName("net.minecraft.class_388", false, loader);
        method(client.getClass(), "method_2928", screen).invoke(client, pause);
        lanProbeChat = null;
        click(pause, 7, true);
        if (!(Boolean) method(server.getClass(), "method_1638").invoke(server)
                || lanProbeChat == null
                || !lanProbeChat.replaceAll("§[0-9a-fk-or]", "").matches("Порт локального сервера: [0-9]+"))
            throw new IllegalStateException("Native pause publication or its chat feedback failed: " + lanProbeChat);
        event("native_survival_share", "listenerOpened=true;nativeChat=true;steamBootstrapSuppressed=true");
    }

    private static void checkSurvivalCommands(Object server) throws Exception {
        ClassLoader loader = server.getClass().getClassLoader();
        Object overworld = method(server.getClass(), "method_2991", int.class).invoke(server, 0);
        Object info = method(overworld.getClass(), "method_3588").invoke(overworld);
        if (!((Enum<?>) method(info.getClass(), "method_233").invoke(info)).name().equals("SURVIVAL")
                || (Boolean) method(info.getClass(), "method_237").invoke(info) != survivalCommandsAllowed)
            throw new IllegalStateException("Native Survival world settings differ from the fixture");
        Object manager = method(server.getClass(), "method_3004").invoke(server);
        if (!((Set<?>) field(manager.getClass(), "field_2715").get(manager)).isEmpty())
            throw new IllegalStateException("Command probe requires an empty OP list");
        List<?> players = (List<?>) field(manager.getClass(), "field_2708").get(manager);
        if (players.size() != 1) throw new IllegalStateException("Command probe requires one native host");
        Class<?> interaction = Class.forName("net.minecraft.class_799", true, loader);
        Class<?> gameWorld = Class.forName("net.minecraft.class_1150", true, loader);
        Class<?> serverType = Class.forName("net.minecraft.server.MinecraftServer", true, loader);
        Class<?> player = Class.forName("net.minecraft.class_798", true, loader);
        Constructor<?> createGuest = player.getConstructor(serverType, gameWorld, String.class, interaction);
        int host = runTimeCommand(server, players.get(0));
        int guest = runTimeCommand(server, createGuest.newInstance(server, overworld, "QaGuest",
            interaction.getConstructor(gameWorld).newInstance(overworld)));
        event("native_survival_commands", "worldCommands=" + survivalCommandsAllowed
            + ";host=" + host + ";guest=" + guest + ";guestFixture=true;opListEmpty=true");
        // The baseline intentionally records a mismatch without preventing save and clean shutdown.
        if (host != (survivalCommandsAllowed ? 1 : 0) || guest != host)
            event("native_survival_command_mismatch", "host=" + host + ";guest=" + guest);
    }

    private static int runTimeCommand(Object server, Object player) throws Exception {
        Class<?> sender = Class.forName("net.minecraft.class_1061", true, server.getClass().getClassLoader());
        Object feedback = Proxy.newProxyInstance(sender.getClassLoader(), new Class<?>[]{sender},
            (proxy, called, args) -> called.getName().equals("method_5505") ? null : called.invoke(player, args));
        Object dispatcher = method(server.getClass(), "method_2971").invoke(server);
        return (Integer) method(dispatcher.getClass(), "method_3310", sender, String.class)
            .invoke(dispatcher, feedback, "time set day");
    }

    private static void verifyGuestMode(Object client, ClassLoader cl) throws Exception {
        Object server = method(client.getClass(), "method_2909").invoke(client);
        Object overworld = method(server.getClass(), "method_2991", int.class).invoke(server, 0);
        Object worldInfo = method(overworld.getClass(), "method_3588").invoke(overworld);
        Enum<?> creative = (Enum<?>)method(worldInfo.getClass(), "method_233").invoke(worldInfo);
        if (!creative.name().equals("CREATIVE")) throw new IllegalStateException("Fixture did not create a Creative overworld");
        Class<?> modeClass = creative.getDeclaringClass();
        Object survival = modeClass.getMethod("valueOf", String.class).invoke(null, "SURVIVAL");
        Method setMode = method(worldInfo.getClass(), "method_207", modeClass);
        verifyShareArguments(client, cl, "CREATIVE");
        try {
            setMode.invoke(worldInfo, survival);
            verifyShareArguments(client, cl, "SURVIVAL");
        } finally {
            setMode.invoke(worldInfo, creative);
        }
        verifyReportsNotice(client, cl, server);
    }

    private static void verifyShareArguments(Object client, ClassLoader cl, String expectedMode) throws Exception {
        Class<?> screen = Class.forName("net.minecraft.class_388", false, cl);
        Object pause = Class.forName("net.minecraft.class_385", true, cl).getConstructor().newInstance();
        Object share = Class.forName("net.minecraft.class_393", true, cl).getConstructor(screen).newInstance(pause);
        method(client.getClass(), "method_2928", screen).invoke(client, share);
        List<?> buttons = (List<?>)field(share.getClass(), "field_1232").get(share);
        if (buttons.contains(field(share.getClass(), "field_1277").get(share))
                || buttons.contains(field(share.getClass(), "field_1278").get(share)))
            throw new IllegalStateException("The authored Nightmare mode/commands controls must stay removed");
        method(client.getClass(), "method_2928", screen).invoke(client, pause);
        int previousCalls = lanProbeCalls;
        lanProbeChat = null;
        click(pause, 7, true);
        boolean expectedCommands = "CREATIVE".equals(expectedMode);
        if (lanProbeCalls != previousCalls + 1 || !expectedMode.equals(lanProbeMode) || lanProbeCommands != expectedCommands)
            throw new IllegalStateException("Unexpected native LAN arguments: " + lanProbeMode + " / commands=" + lanProbeCommands);
        if (lanProbeChat == null || !lanProbeChat.replaceAll("§[0-9a-fk-or]", "").equals("Порт локального сервера: 25565"))
            throw new IllegalStateException("Unexpected native LAN success message: " + lanProbeChat);
        event("native_guest_mode_verified", "worldMode=" + expectedMode + ";guestMode=" + lanProbeMode
            + ";commands=" + lanProbeCommands + ";authorControlsHidden=true;oneClickPause=true;networkSuppressedByQa=true");
        event("native_share_chat_verified", "locale=ru_RU;messageMatches=true;syntheticPort=25565");
    }

    private static void verifyNativeAudio(Object client, ClassLoader cl) throws Exception {
        Object manager=field(client.getClass(),"field_3759").get(client);
        Class<?> accessor=Class.forName("com.itlesports.nightmaremode.mixin.interfaces.SoundManagerAccess",false,cl);
        Object system=accessor.getMethod("getSoundSystem").invoke(manager);
        Object pool=accessor.getMethod("getSoundPoolSounds").invoke(manager);
        Object backend=field(system.getClass(),"soundLibrary").get(system);
        Class<?> library=backend==null?null:backend.getClass();
        boolean created=(Boolean)Class.forName("org.lwjgl.openal.AL",false,cl).getMethod("isCreated").invoke(null);
        Object click=method(pool.getClass(),"method_1721",String.class).invoke(pool,"random.click");
        if(!created||library==null||!library.getName().contains("OpenAL")||click==null)
            throw new IllegalStateException("Native OpenAL/click resource is unavailable: "+library+" / "+created);
        java.net.URL url=(java.net.URL)method(click.getClass(),"method_5984").invoke(click);
        try(java.io.InputStream input=url.openStream()) {if(input.read()<0)throw new IllegalStateException("Empty native click resource");}
        event("native_audio_resource_verified","OpenALCreated=true;library="+library.getSimpleName()+";clickResourceReadable=true;urlProtocol="+url.getProtocol()+";audiblePlaybackNotClaimed=true");
    }

    private static void verifySharingChat(Object client, ClassLoader cl) throws Exception {
        Class<?> gui=Class.forName("net.minecraft.class_388",false,cl);
        Object parent=field(client.getClass(),"field_3816").get(client);
        Object screen=Class.forName("net.minecraft.class_359",true,cl).getConstructor().newInstance();
        Method show=method(client.getClass(),"method_2928",gui);
        Class<?> entry=Class.forName("link.e4steam.E4steamClient",true,cl);
        Field current=field(entry,"session");Object original=current.get(null);
        Class<?> access=Class.forName("link.e4steam.steam.SteamAccessMode",true,cl);
        Object friends=access.getMethod("valueOf",String.class).invoke(null,"FRIENDS_ONLY");
        Class<?> sessionClass=Class.forName("link.e4steam.steam.SteamSession",true,cl);
        Object fake=sessionClass.getConstructor(int.class,access).newInstance(25565,friends);
        Field state=field(sessionClass,"state");
        state.set(fake,state.getType().getMethod("valueOf",String.class).invoke(null,"STARTED"));
        Class<?> controls=Class.forName("link.e4steam.legacy164.LegacySharingChat",true,cl);
        Method click=controls.getMethod("clickAt",int.class,int.class,int.class);
        try {
            current.set(null,fake);show.invoke(client,screen);
            controls.getMethod("show",String.class).invoke(null,"s-qa-synthetic.steam");
            Object chat=method(field(client.getClass(),"field_3820").get(client).getClass(),"method_983").invoke(field(client.getClass(),"field_3820").get(client));
            Object font=field(client.getClass(),"field_3814").get(client);
            Object settings=field(client.getClass(),"field_3823").get(client);
            Class<?> resolution=Class.forName("net.minecraft.class_389",true,cl);
            Object scaleObject=resolution.getConstructor(settings.getClass(),int.class,int.class).newInstance(settings,
                field(client.getClass(),"field_3801").getInt(client),field(client.getClass(),"field_3802").getInt(client));
            int scale=(Integer)method(resolution,"method_1049").invoke(scaleObject);
            float chatScale=(Float)method(chat.getClass(),"method_4938").invoke(chat);
            int fontHeight=field(font.getClass(),"field_1141").getInt(font);
            List<?> lines=(List<?>)field(chat.getClass(),"field_5713").get(chat);
            int stopX=0,stopY=0,checked=0;
            String[] labels={"s-qa-synthetic.steam","[Пригласить друзей Steam]","[Закрыть доступ]"};
            String[] commands={"/e4steam copy","/e4steam invite","/e4steam stop"};
            for(int action=0;action<labels.length;action++) {
                boolean found=false;
                for(int lineIndex=0;lineIndex<lines.size();lineIndex++) {
                    Object line=lines.get(lineIndex);
                    String plain=((String)method(line.getClass(),"method_834").invoke(line)).replaceAll("§.","");
                    int at=plain.indexOf(labels[action]);if(at<0)continue;
                    int pixels=(Integer)method(font.getClass(),"method_954",String.class).invoke(font,plain.substring(0,at));
                    int x=Math.round((pixels*chatScale+3)*scale)+scale;
                    int y=Math.round(((lineIndex*(fontHeight+1)+fontHeight/2f)*chatScale+25)*scale);
                    sharingCommand=null;
                    if(!(Boolean)click.invoke(null,x,y,0)||!commands[action].equals(sharingCommand))
                        throw new IllegalStateException("Native sharing hit-test failed for "+commands[action]+": "+sharingCommand);
                    if(action==2){stopX=x;stopY=y;}found=true;checked++;break;
                }
                if(!found)throw new IllegalStateException("Native sharing label missing: "+labels[action]);
            }
            current.set(null,null);sharingCommand=null;
            if((Boolean)click.invoke(null,stopX,stopY,0)||sharingCommand!=null)
                throw new IllegalStateException("A stale sharing line dispatched a command");
            event("native_sharing_controls_verified","nativeFontAndWrap=true;copyInviteStop="+checked+";staleRejected=true;commandsSuppressedByQa=true;physicalMouseNotUsed=true");
            Class<?> component=Class.forName("net.minecraft.class_1687",true,cl);
            Object[] arguments={"s0000000000001","s0000000000002"};
            Object message=component.getMethod("method_6020",String.class,Object[].class).invoke(null,"commands.tp.success",arguments);
            String rendered=(String)component.getMethod("method_6015",boolean.class).invoke(message,false);
            if(!rendered.contains("QaHost")||!rendered.contains("QaGuest")||rendered.contains("s000000000000"))
                throw new IllegalStateException("Native command translation lost display names: "+rendered);
            if(!arguments[0].equals("s0000000000001"))throw new IllegalStateException("Command arguments were mutated");
            event("native_command_names_verified","translation=commands.tp.success;twoRegisteredLabels=true;canonicalArgumentsPreserved=true");
            Object body=component.getMethod("method_6020",String.class,Object[].class).invoke(null,"commands.gamemode.success.self",new Object[]{"Creative Mode"});
            Object[] adminArguments={"s0000000000001",body};
            Object admin=component.getMethod("method_6020",String.class,Object[].class).invoke(null,"chat.type.admin",adminArguments);
            String adminText=((String)component.getMethod("method_6015",boolean.class).invoke(admin,false)).replaceAll("§.","");
            if(!adminText.startsWith("[QaHost:")||adminText.contains("s0000000000001")||!adminArguments[0].equals("s0000000000001")||adminArguments[1]!=body)
                throw new IllegalStateException("Native admin feedback lost display name or canonical arguments: "+adminText);
            event("native_admin_name_verified","translation=chat.type.admin;registeredSenderDisplayed=true;nestedBodyAndCanonicalArgumentsPreserved=true");
        } finally {current.set(null,original);show.invoke(client,parent);}
    }

    private static void audioProbe(Object client,ClassLoader cl)throws Exception {
        if(Files.isRegularFile(evidence.resolve("stop.flag"))){ended=true;method(client.getClass(),"method_2949").invoke(client);event("native_audio_probe_stop","normalGameShutdown=true");return;}
        String[] sounds={"random.click","step.grass","step.stone","step.sand","mob.zombie.say","ambient.weather.rain"};
        Object manager=field(client.getClass(),"field_3759").get(client);
        Class<?> accessor=Class.forName("com.itlesports.nightmaremode.mixin.interfaces.SoundManagerAccess",false,cl);
        Object pool=accessor.getMethod("getSoundPoolSounds").invoke(manager);
        if(audioReadyAt==0){
            Map<?,?> entries=null;
            for(Field f:pool.getClass().getDeclaredFields())if(Map.class.isAssignableFrom(f.getType())){f.setAccessible(true);entries=(Map<?,?>)f.get(pool);break;}
            if(entries==null)throw new IllegalStateException("Native sound pool map missing");
            List<String> rows=new ArrayList<>();
            for(String name:sounds){Object value=entries.get(name);int count=value instanceof List<?>?((List<?>)value).size():0;rows.add(name+"\t"+count);}
            Files.write(evidence.resolve("audio-pool.tsv"),rows,StandardCharsets.UTF_8);
            List<String> pools=new ArrayList<>();
            for(String poolField:List.of("field_2260","field_2261","field_2262")){
                Object nativePool=field(manager.getClass(),poolField).get(manager);
                for(Field f:nativePool.getClass().getDeclaredFields())if(Map.class.isAssignableFrom(f.getType())){f.setAccessible(true);pools.add(poolField+"\t"+((Map<?,?>)f.get(nativePool)).size());}
            }
            Files.write(evidence.resolve("audio-all-pools.tsv"),pools,StandardCharsets.UTF_8);
            Class<?> al=Class.forName("org.lwjgl.openal.AL",false,cl),deviceType=Class.forName("org.lwjgl.openal.ALCdevice",false,cl);
            Object device=al.getMethod("getDevice").invoke(null);
            String endpoint=(String)Class.forName("org.lwjgl.openal.ALC10",false,cl).getMethod("alcGetString",deviceType,int.class).invoke(null,device,0x1013);
            event("native_audio_probe_ready","poolKeys="+entries.size()+";device="+endpoint.replace('\0','|')+";noWorldOpened=true");
            Files.writeString(evidence.resolve("audio-ready"),"ready\n");audioReadyAt=System.nanoTime();
        }
        if(audioStartedAt==0){
            if(!Files.isRegularFile(evidence.resolve("emit.flag"))){if(System.nanoTime()-audioReadyAt>90_000_000_000L)throw new IllegalStateException("Capture emission gate timeout");return;}
            audioStartedAt=System.nanoTime();
        }
        long elapsed=(System.nanoTime()-audioStartedAt)/1_000_000L;
        if(audioSoundIndex<sounds.length&&elapsed>=audioSoundIndex*1200L){
            String name=sounds[audioSoundIndex++];
            method(manager.getClass(),"method_1710",String.class,float.class,float.class).invoke(manager,name,.25F,1F);
            event("native_audio_probe_emit","key="+name+";volume=.25;nativeSoundManager=true");
        }
        if(!audioMusicStarted&&elapsed>=7200){
            audioMusicStarted=true;Object settings=field(client.getClass(),"field_3823").get(client);
            Field music=field(settings.getClass(),"field_960");audioOriginalMusic=music.getFloat(settings);music.setFloat(settings,.25F);
            field(manager.getClass(),"field_2267").setInt(manager,0);
            method(manager.getClass(),"method_1717").invoke(manager);
            event("native_audio_probe_emit","key=music;volume=.25;nativeSoundManager=true;onlyQaCooldownChanged=true");
        }
        if(elapsed>=12500){
            if(audioOriginalMusic>=0){Object settings=field(client.getClass(),"field_3823").get(client);field(settings.getClass(),"field_960").setFloat(settings,audioOriginalMusic);}
            ended=true;method(client.getClass(),"method_2949").invoke(client);event("native_audio_probe_shutdown","noWorldOpened=true;normalGameShutdown=true");
        }
    }

    private static void verifyReportsNotice(Object client, ClassLoader cl, Object server) throws Exception {
        Class<?> screen = Class.forName("net.minecraft.class_388", false, cl);
        Class<?> pauseType = Class.forName("net.minecraft.class_385", true, cl);
        Method showScreen = method(client.getClass(), "method_2928", screen);
        Object before = pauseType.getConstructor().newInstance();
        showScreen.invoke(client, before);
        boolean canQuit = field(pauseButton(before, 1).getClass(), "field_1055").getBoolean(pauseButton(before, 1));
        Field published = field(server.getClass(), "field_2208");
        if (published.getBoolean(server)) throw new IllegalStateException("The offline publication probe started a real listener");
        // Emulate only the observed publish state. No Steam session or listener exists.
        published.setBoolean(server, true);
        try {
            Object pause = pauseType.getConstructor().newInstance();
            showScreen.invoke(client, pause);
            Object reports = pauseButton(pause, 7);
            Object quit = pauseButton(pause, 1);
            if (!"Жалобы".equals(field(reports.getClass(), "field_1053").get(reports))
                    || !field(reports.getClass(), "field_1055").getBoolean(reports)
                    || field(quit.getClass(), "field_1055").getBoolean(quit) != canQuit)
                throw new IllegalStateException("Native reports caption/action or author quit gate mismatch");
            click(pause, 7, true);
            Field currentScreen = field(client.getClass(), "field_3816");
            Object notice = currentScreen.get(client);
            if (!notice.getClass().getName().equals("net.minecraft.class_475")
                    || !"Жалобы".equals(field(notice.getClass(), "field_1638").get(notice))
                    || !"Жалобы на игроков в этой версии недоступны.".equals(field(notice.getClass(), "field_1639").get(notice)))
                throw new IllegalStateException("Native reports notice did not display the expected copy");
            click(notice, 0, true);
            if (currentScreen.get(client) != pause)
                throw new IllegalStateException("Native report notice did not return to the same pause screen");
            event("native_reports_notice_verified", "locale=ru_RU;authorQuitGatePreserved=true;returnsToSamePause=true;syntheticPublishedState=true");
        } finally {
            published.setBoolean(server, false);
            showScreen.invoke(client, new Object[]{null});
        }
    }

    private static Object pauseButton(Object screen, int id) throws Exception {
        for (Object button : (List<?>)field(screen.getClass(), "field_1232").get(screen))
            if (field(button.getClass(), "field_1054").getInt(button) == id) return button;
        throw new IllegalStateException("Native screen button is missing: " + id);
    }

    private static void verifyAchievementMixins(ClassLoader cl) throws Exception {
        for (String owner : List.of("api.achievement.AchievementEventDispatcher",
                "com.itlesports.nightmaremode.item.items.template.ItemAchievementGranter")) {
            Class<?> target = Class.forName(owner, false, cl);
            long redirects = Arrays.stream(target.getDeclaredMethods())
                .filter(method -> method.getName().endsWith("$achievementDisplayName")).count();
            if (redirects != 1) throw new IllegalStateException("Expected one applied achievement display redirect: " + owner);
            event("native_achievement_display_redirect_bound", "target=" + owner + ";announcementNotTriggered=true");
        }
    }
    private static void nameMechanics(Object client,Object player,ClassLoader cl)throws Exception {
        if(nameMechanicsDone)return;
        if(appleFailure!=null)throw new IllegalStateException("Native shadow-player death probe failed",appleFailure);
        if(nameRegistry==null) {
            nameRegistry=Path.of(System.getProperty("minecraft.portable.legacy.registry")).toRealPath();if(!nameRegistry.startsWith(fixture))throw new SecurityException("Name registry escaped fixture");originalNameRegistry=Files.readAllBytes(nameRegistry);
        }
        String nativeName=(String)field(player.getClass(),"field_3982").get(player);
        if(namePhase==0) {
            updatePrivateName(NAME_CASES[nameStage]);nameStarted=worldTicks;namePhase=1;appleResult=null;
            event("native_name_case_requested","case="+NAME_CASES[nameStage]+";privateFixtureRegistryOnly=true;realHostNotKilled=true");return;
        }
        if(namePhase==1) {
            if(worldTicks-nameStarted<20)return;
            if(nameStage<=1) {
                Object server=method(client.getClass(),"method_2909").invoke(client);Object[] worlds=(Object[])field(server.getClass(),"field_3850").get(server);
                Object shadow=Class.forName("net.minecraft.class_519",true,cl).getConstructor(Class.forName("net.minecraft.class_1150",false,cl),String.class).newInstance(worlds[0],nativeName);
                method(shadow.getClass(),"method_2522",double.class,double.class,double.class).invoke(shadow,field(player.getClass(),"field_3252").getDouble(player)+2,field(player.getClass(),"field_3253").getDouble(player)+0.5,field(player.getClass(),"field_3254").getDouble(player));
                SERVER_ACTIONS.add(s->{try{appleResult=nativeNotchDeath(s,shadow,cl);}catch(Throwable t){appleFailure=t;}});
            }
            inventoryScreen=Class.forName("net.minecraft.class_422",true,cl).getConstructor(Class.forName("net.minecraft.class_988",false,cl)).newInstance(player);
            nameEarsBefore=earCalls;nameFlipsBefore=upsideDownCalls;nameStarted=worldTicks;
            method(client.getClass(),"method_2928",Class.forName("net.minecraft.class_388",false,cl)).invoke(client,inventoryScreen);namePhase=2;return;
        }
        if(worldTicks-nameStarted<25)return;
        if(nameStage<=1&&appleResult==null)return;
        long ears=earCalls-nameEarsBefore,flips=upsideDownCalls-nameFlipsBefore;
        boolean capeHidden=(Boolean)method(player.getClass(),"method_4577").invoke(player);
        if((nameStage<=1&&appleResult!=(nameStage==1?1:0))||(nameStage==4&&ears<2)||(nameStage!=4&&ears!=0)||(nameStage>=2&&nameStage<=3&&!capeHidden&&flips<=0)||(nameStage!=2&&nameStage!=3&&flips!=0))throw new IllegalStateException("Native name-trigger behavior mismatch: "+NAME_CASES[nameStage]+";apple="+appleResult+";ears="+ears+";flip="+flips+";capeHidden="+capeHidden);
        Object session=method(client.getClass(),"method_5568").invoke(client);
        if(!nativeName.equals(method(session.getClass(),"method_5583").invoke(session)))throw new IllegalStateException("Name probe changed native Session identity");
        event("native_name_mechanics_observed","case="+NAME_CASES[nameStage]+";redAppleDrop="+(appleResult==null?"not_requested":appleResult)+";earDrawCalls="+ears+";upsideDownRotations="+flips+";nativeCapeVisibilityGuard="+capeHidden+";canonicalUsernameAndSessionUnchanged=true;shadowNeverAdmitted=true");
        method(client.getClass(),"method_2928",Class.forName("net.minecraft.class_388",false,cl)).invoke(client,new Object[]{null});nameStage++;namePhase=0;
        if(nameStage==NAME_CASES.length){replaceNameRegistry(originalNameRegistry);originalNameRegistry=null;nameMechanicsDone=true;event("native_name_registry_restored","privateDisplayRegistryRestored=true;personalSettingsWritten=false");}
    }
    private static int nativeNotchDeath(Object server,Object shadow,ClassLoader cl)throws Exception {
        Object world=field(shadow.getClass(),"field_3248").get(shadow);List<?> entities=(List<?>)field(world.getClass(),"field_4541").get(world);Set<Object> before=Collections.newSetFromMap(new IdentityHashMap<>());before.addAll(entities);
        Object manager=method(server.getClass(),"method_3004").invoke(server);List<?> admitted=(List<?>)field(manager.getClass(),"field_2708").get(manager);if(admitted.size()!=1||admitted.contains(shadow))throw new SecurityException("Shadow was admitted as a network player");
        Class<?> base=Class.forName("net.minecraft.class_988",false,cl),damage=Class.forName("net.minecraft.class_856",false,cl);Object source=field(damage,"field_3137").get(null);
        try{java.lang.invoke.MethodHandles.Lookup lookup=java.lang.invoke.MethodHandles.privateLookupIn(base,java.lang.invoke.MethodHandles.lookup());lookup.findSpecial(base,"method_2580",java.lang.invoke.MethodType.methodType(void.class,damage),base).invokeWithArguments(shadow,source);}catch(Throwable failure){throw new IllegalStateException(failure);}
        Class<?> item=Class.forName("net.minecraft.class_964",false,cl);int apples=0;
        for(Object entity:new ArrayList<>(entities))if(!before.contains(entity)&&item.isInstance(entity)){
            Object stack=method(entity.getClass(),"method_4548").invoke(entity);if(field(stack.getClass(),"field_4378").getInt(stack)==260)apples+=field(stack.getClass(),"field_4376").getInt(stack);method(entity.getClass(),"method_2563").invoke(entity);
        }
        if(admitted.size()!=1||admitted.contains(shadow))throw new SecurityException("Shadow altered network admission");return apples;
    }
    private static void updatePrivateName(String value)throws IOException {
        String uuid=System.getProperty("minecraft.portable.identity.localPlayer","");List<String> lines=new ArrayList<>(Files.readAllLines(nameRegistry));boolean changed=false;
        for(int i=0;i<lines.size();i++){String[] f=lines.get(i).split("\\|",-1);if(f.length==4&&f[3].equalsIgnoreCase(uuid)){f[2]=Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));lines.set(i,String.join("|",f));changed=true;}}
        if(!changed)throw new SecurityException("Private local name row was missing");replaceNameRegistry(String.join("\n",lines).concat("\n").getBytes(StandardCharsets.UTF_8));
    }
    private static void replaceNameRegistry(byte[] bytes)throws IOException {Path temp=nameRegistry.resolveSibling(nameRegistry.getFileName()+".qa-name-temp");Files.write(temp,bytes);Files.move(temp,nameRegistry,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
    private static void syntheticNativeSteamRow(Object screen,Object poll,ClassLoader cl)throws Exception {
        Class<?> worldRecord=Class.forName("link.e4steam.legacy164.LegacyDiscovery$World",true,cl);
        Constructor<?> ctor=worldRecord.getDeclaredConstructor(long.class,long.class,String.class,String.class);ctor.setAccessible(true);
        Object mock=ctor.newInstance(1L,2L,"QaHostFixture","QaWorldFixture");
        method(poll.getClass(),"apply",Map.class).invoke(poll,Map.of("e4steam-legacy-1.invalid",mock));
        method(screen.getClass(),"method_1033").invoke(screen);
        Object list=field(screen.getClass(),"field_1197").get(screen);List<?> entries=(List<?>)field(list.getClass(),"field_2225").get(list);int before=entries.size();
        method(list.getClass(),"method_1659",String.class,java.net.InetAddress.class).invoke(list,"[MOTD]Private native LAN QA[/MOTD][AD]25565[/AD]",java.net.InetAddress.getLoopbackAddress());
        if(before!=1||entries.size()!=1)throw new IllegalStateException("Native LAN row escaped managed Steam list");
        Object renderer=field(screen.getClass(),"field_1185").get(screen),saved=field(screen.getClass(),"field_1186").get(screen);
        int offset=((Number)method(saved.getClass(),"method_1281").invoke(saved)).intValue();Class<?> tessellator=Class.forName("net.minecraft.class_533",false,cl);Object tess=field(tessellator,"field_1945").get(null);
        ROW_TEXT.clear();capturingRowText=true;
        try{method(renderer.getClass(),"method_1018",int.class,int.class,int.class,int.class,tessellator).invoke(renderer,offset,40,60,36,tess);}finally{capturingRowText=false;}
        Files.write(evidence.resolve("synthetic-native-row-texts.txt"),ROW_TEXT,StandardCharsets.UTF_8);
        if(!ROW_TEXT.stream().anyMatch(t->t.contains("QaWorldFixture"))||!ROW_TEXT.stream().anyMatch(t->t.contains("QaHostFixture"))||!ROW_TEXT.contains("Steam")||ROW_TEXT.stream().anyMatch(t->t.contains(".invalid")))throw new IllegalStateException("Native Steam row labels were not rendered correctly");
        method(poll.getClass(),"apply",Map.class).invoke(poll,Map.of());method(screen.getClass(),"method_1033").invoke(screen);
        event("synthetic_native_steam_row_rendered","actualGuiSlotRenderer=true;worldHostSteamTextObserved=true;noInvalidAddressDrawn=true;nativeLanAnnouncementBlocked=true;syntheticUiFixtureOnly=true;actualFriendDiscoveryPass=false");
    }
    private static void startLobbyMetadataObservation(Object runtime,int port,String name,ClassLoader cl)throws Exception {
        String expectedPlayer=(String)Class.forName("link.e4steam.legacy164.LegacyRegistry",true,cl).getMethod("displayName",String.class).invoke(null,name);
        lobbyMetadataStarted=true;Thread worker=new Thread(()->{
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(95);
            try {
                while(System.nanoTime()<deadline) {
                    java.util.concurrent.Callable<String> query=()->{
                        Object host=field(runtime.getClass(),"hostRegistration").get(runtime),social=field(runtime.getClass(),"lobbyManager").get(runtime);
                        if(host==null||social==null)return "WAIT";
                        Object owner=method(host.getClass(),"owner").invoke(host);
                        if(((Number)method(host.getClass(),"localPort").invoke(host)).intValue()!=port||field(social.getClass(),"hostLobbyOwner").get(social)!=owner)return "WAIT";
                        long id=field(social.getClass(),"hostLobbyId").getLong(social);if(id==0)return "WAIT";
                        Class<?> steamId=Class.forName("com.codedisaster.steamworks.SteamID",true,cl);Object lobby=steamId.getMethod("createFromNativeHandle",long.class).invoke(null,id),matchmaking=field(social.getClass(),"matchmaking").get(social);
                        Object nativeOwner=method(matchmaking.getClass(),"getLobbyOwner",steamId).invoke(matchmaking,lobby);
                        Class<?> handle=Class.forName("com.codedisaster.steamworks.SteamNativeHandle",true,cl);
                        long actualOwner=((Number)handle.getMethod("getNativeHandle",handle).invoke(null,nativeOwner)).longValue();
                        String own=(String)method(runtime.getClass(),"steamId").invoke(runtime);
                        if(actualOwner!=Long.parseUnsignedLong(own))return "WRONG_OWNER";
                        Method data=method(matchmaking.getClass(),"getLobbyData",steamId,String.class);
                        String marker=(String)data.invoke(matchmaking,lobby,"lanminecraft_display"),player=(String)data.invoke(matchmaking,lobby,"lanminecraft_player"),title=(String)data.invoke(matchmaking,lobby,"lanminecraft_world");
                        return "1".equals(marker)&&expectedPlayer.equals(player)&&world.equals(title)?"PASS":"WAIT";
                    };
                    Object future=method(runtime.getClass(),"submitSteamTask",java.util.concurrent.Callable.class).invoke(runtime,query);
                    String result=(String)((java.util.concurrent.Future<?>)future).get(8,java.util.concurrent.TimeUnit.SECONDS);
                    if(!result.equals("WAIT")){lobbyMetadataResult=result;return;}Thread.sleep(250);
                }
                lobbyMetadataResult="TIMEOUT";
            }catch(Throwable error){lobbyMetadataResult="ERROR:"+error.getClass().getSimpleName();}
        },"BTW-QA-own-Steam-lobby-description");worker.setDaemon(true);worker.start();
    }
    private static void nativeProgress(Object server,String action)throws Exception {
        ClassLoader cl=server.getClass().getClassLoader();
        Object manager=method(server.getClass(),"method_3004").invoke(server);
        List<?> players=(List<?>)field(manager.getClass(),"field_2708").get(manager);
        if(players.size()!=1)throw new SecurityException("Progress probe requires one private native player");
        Object player=players.get(0),actualWorld=field(player.getClass(),"field_3248").get(player);
        String name=(String)field(player.getClass(),"field_3982").get(player);
        Class<?> registry=Class.forName("link.e4steam.legacy164.LegacyRegistry",true,cl);
        Object uuid=registry.getMethod("uuid",String.class).invoke(null,name);
        if(!String.valueOf(uuid).equalsIgnoreCase(System.getProperty("minecraft.portable.identity.localPlayer","")))throw new SecurityException("Wrong private progress profile");
        Object inventory=field(player.getClass(),"field_3999").get(player),items=field(inventory.getClass(),"field_3964").get(inventory);
        Path receipt=fixture.resolve("private/native-progress.properties");Class<?> wolfType=Class.forName("net.minecraft.class_954",true,cl);
        if(action.equals("seed")) {
            if(Files.exists(receipt)||java.lang.reflect.Array.get(items,8)!=null)throw new SecurityException("Progress seed would overwrite existing fixture data");
            Object stack=Class.forName("net.minecraft.class_1071",true,cl).getConstructor(int.class,int.class,int.class).newInstance(280,7,0);
            method(stack.getClass(),"method_4622",String.class).invoke(stack,"BtwQaProgress");java.lang.reflect.Array.set(items,8,stack);
            method(player.getClass(),"method_3220",int.class).invoke(player,17);
            Object wolf=wolfType.getConstructor(Class.forName("net.minecraft.class_1150",false,cl)).newInstance(actualWorld);
            method(wolf.getClass(),"method_2715",boolean.class).invoke(wolf,true);method(wolf.getClass(),"method_2713",String.class).invoke(wolf,name);method(wolf.getClass(),"method_2716",boolean.class).invoke(wolf,true);
            double x=field(player.getClass(),"field_3252").getDouble(player),y=field(player.getClass(),"field_3253").getDouble(player),z=field(player.getClass(),"field_3254").getDouble(player);
            method(wolf.getClass(),"method_2522",double.class,double.class,double.class).invoke(wolf,x+1.5,y+0.5,z);
            if(!(Boolean)method(actualWorld.getClass(),"method_3686",Class.forName("net.minecraft.class_864",false,cl)).invoke(actualWorld,wolf))throw new IllegalStateException("Native tamed fixture wolf did not spawn");
            Properties expected=new Properties();expected.setProperty("world",world);expected.setProperty("xp",String.valueOf(field(player.getClass(),"field_4003").getInt(player)));expected.setProperty("itemId","280");expected.setProperty("count","7");
            Files.createDirectories(receipt.getParent());try(OutputStream out=Files.newOutputStream(receipt)){expected.store(out,"Private native progress fixture; no Steam/account data");}
            event("native_progress_seeded","serverThread=true;nativeItemId=280;count=7;xpAdded=17;nativeTamedWolfOwnerAssigned=true;productionRulesChanged=false");
        } else {
            Properties expected=new Properties();try(InputStream in=Files.newInputStream(receipt)){expected.load(in);}
            if(!world.equals(expected.getProperty("world")))throw new SecurityException("Wrong progress world");
            Object stack=java.lang.reflect.Array.get(items,8);
            if(stack==null||field(stack.getClass(),"field_4378").getInt(stack)!=280||field(stack.getClass(),"field_4376").getInt(stack)!=7||field(player.getClass(),"field_4003").getInt(player)!=Integer.parseInt(expected.getProperty("xp")))throw new IllegalStateException("Native inventory/experience did not survive reentry");
            boolean owner=false;
            for(Object entity:(List<?>)field(actualWorld.getClass(),"field_4541").get(actualWorld))if(wolfType.isInstance(entity)&&name.equals(method(entity.getClass(),"method_2719").invoke(entity))) {
                if(!(Boolean)method(entity.getClass(),"method_2717").invoke(entity))throw new IllegalStateException("Native wolf lost tame state");
                owner=method(entity.getClass(),"method_6183").invoke(entity)==player;
                if(owner)break;
            }
            if(!owner)throw new IllegalStateException("Native tamed wolf did not resolve its original owner");
            String display=(String)registry.getMethod("displayName",String.class).invoke(null,name);
            String shown=(String)method(player.getClass(),"method_5381").invoke(player);
            if(!shown.equals(display))throw new IllegalStateException("Native displayed nickname did not follow the private registry");
            event("native_progress_after_rename_verified","serverThread=true;nativeItemId=280;count=7;experienceRestored=true;tamedWolfSameOwner=true;nativeFriendlyNameMatches=true;twoAccountSteamPass=false");
        }
    }
    private static int checkSelectedPixels(java.awt.image.BufferedImage actual)throws Exception {
        String configured=System.getProperty("btw.qa.expectedSkinPng","");if(configured.isBlank())return 0;
        Path p=Path.of(configured).toRealPath();if(!p.startsWith(fixture))throw new SecurityException("Expected skin escaped QA fixture");
        java.awt.image.BufferedImage expected=javax.imageio.ImageIO.read(p.toFile());int compared=0;
        for(int[] r:new int[][]{{8,8,8,8},{20,20,8,12},{4,20,4,12},{44,20,3,12}})
            for(int y=r[1];y<r[1]+r[3];y++)for(int x=r[0];x<r[0]+r[2];x++) {
                int a=expected.getRGB(x,y),b=actual.getRGB(x,y);if((a>>>24)!=255)continue;compared++;
                if((a&0xffffff)!=(b&0xffffff)||(b>>>24)==0)throw new IllegalStateException("Selected opaque base-skin pixel did not reach native image");
            }
        if(compared<24)throw new IllegalStateException("Selected skin lacks enough opaque signature pixels");return compared;
    }
    private static void dynamicSkin(Object player,ClassLoader cl)throws Exception {
        if(worldTicks<90||dynamicStage==3)return;
        String name=(String)field(player.getClass(),"field_3982").get(player),uuid=System.getProperty("minecraft.portable.identity.localPlayer","");
        boolean original=System.getProperty("btw.qa.expectedSkinModel","classic").equals("slim");
        if(dynamicStage==0) {
            skinRegistry=Path.of(System.getProperty("minecraft.portable.skin.registry")).toRealPath();
            if(!skinRegistry.startsWith(fixture))throw new SecurityException("Skin registry escaped fixture");
            originalSkinRegistry=Files.readAllBytes(skinRegistry);oldSkinTexture=method(player.getClass(),"method_5723").invoke(player);
            List<String> lines=new ArrayList<>(Files.readAllLines(skinRegistry));boolean changed=false;
            for(int i=0;i<lines.size();i++){String[] f=lines.get(i).split("\\|",-1);if(f.length==4&&f[0].equalsIgnoreCase(uuid)){f[2]=original?"classic":"slim";lines.set(i,String.join("|",f));changed=true;}}
            if(!changed)throw new IllegalStateException("Local selected skin registry row missing");replaceRegistry(String.join("\n",lines).concat("\n").getBytes(StandardCharsets.UTF_8));dynamicStage=1;dynamicStarted=worldTicks;
            event("dynamic_skin_model_requested","privateFixtureRegistryOnly=true;originalSelectedImagePreserved=true");return;
        }
        if(worldTicks-dynamicStarted<15)return;
        Object value=Class.forName("api.client.mojapi.ProfileUtils",true,cl).getMethod("getUserProfile",String.class,boolean.class).invoke(null,name,false);
        Object profile=((Optional<?>)value).orElseThrow();boolean expected=dynamicStage==1?!original:original;
        Object texture=method(player.getClass(),"method_5723").invoke(player);
        java.awt.image.BufferedImage image=(java.awt.image.BufferedImage)field(texture.getClass(),"field_6550").get(texture);
        if((Boolean)profile.getClass().getMethod("isSlim").invoke(profile)!=expected||texture==oldSkinTexture||image==null)return;
        boolean reset=field(oldSkinTexture.getClass(),"field_6544").getInt(oldSkinTexture)==-1;
        int allocated=((Number)method(texture.getClass(),"method_5855").invoke(texture)).intValue(),pixels=checkSelectedPixels(image);
        Map<?,?> cache=(Map<?,?>)field(Class.forName("net.minecraft.class_570",true,cl),"isSlimSkinMap").get(null);
        Object cached=cache.get(name);if(!reset||allocated<=0||(cached!=null&&!cached.equals(expected)))throw new IllegalStateException("Native skin/model cache was not invalidated");
        event("dynamic_skin_model_observed","stage="+dynamicStage+";slim="+expected+";oldTextureObjectReset=true;newTextureObject=true;selectedOpaquePixelsMatched="+pixels+";nativeModelCacheCorrect=true");
        if(dynamicStage==1){oldSkinTexture=texture;replaceRegistry(originalSkinRegistry);dynamicStage=2;dynamicStarted=worldTicks;}
        else{dynamicStage=3;originalSkinRegistry=null;event("dynamic_selected_skin_restored","originalPrivateRegistryRestored=true;personalFilesWritten=false");}
    }
    private static void replaceRegistry(byte[] data)throws IOException {
        Path tmp=skinRegistry.resolveSibling(skinRegistry.getFileName()+".qa-temp");Files.write(tmp,data);Files.move(tmp,skinRegistry,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    private static void startRawTcpNegative(int port,String nativeName,ClassLoader cl) {
        rawTcpStarted=true;Thread worker=new Thread(()->{
            try(java.net.Socket socket=new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress("127.0.0.1",port),3000);socket.setSoTimeout(5000);
                DataOutputStream output=new DataOutputStream(socket.getOutputStream());output.writeByte(2);
                Object packet=Class.forName("net.minecraft.class_652",true,cl).getConstructor(int.class,String.class,String.class,int.class).newInstance(78,nativeName,"127.0.0.1",port);
                method(packet.getClass(),"method_6040",DataOutput.class).invoke(packet,output);output.flush();
                DataInputStream input=new DataInputStream(socket.getInputStream());int id=input.readUnsignedByte();
                if(id!=255)throw new IllegalStateException("Native rejected-login packet was not disconnect");
                int len=input.readUnsignedShort();if(len>1024)throw new IllegalStateException("Oversized disconnect reason");StringBuilder reason=new StringBuilder();for(int i=0;i<len;i++)reason.append(input.readChar());
                rawTcpResult=reason.toString().equals("An authenticated Steam connection is required")?"DENIED":"WRONG_REASON";
            }catch(Throwable failure){rawTcpResult="ERROR:"+failure.getClass().getSimpleName();}
        },"BTW-QA-raw-TCP-negative");worker.setDaemon(true);worker.start();
    }

    private static boolean click(Object screen,int id,boolean required) throws Exception {
        for(Object button:(List<?>)field(screen.getClass(),"field_1232").get(screen)) {
            if(field(button.getClass(),"field_1054").getInt(button)!=id)continue;
            if(!field(button.getClass(),"field_1055").getBoolean(button)){if(required)throw new IllegalStateException("Native button disabled");return false;}
            method(screen.getClass(),"method_1027",button.getClass()).invoke(screen,button);return true;
        }
        throw new IllegalStateException("Native button missing: "+id);
    }
    private static void recordMods(ClassLoader cl)throws Exception {
        Class<?> loader=Class.forName("net.fabricmc.loader.api.FabricLoader",true,cl);
        Object instance=loader.getMethod("getInstance").invoke(null);
        Class<?> container=Class.forName("net.fabricmc.loader.api.ModContainer",true,cl), metadata=Class.forName("net.fabricmc.loader.api.metadata.ModMetadata",true,cl);
        List<String> rows=new ArrayList<>(), origins=new ArrayList<>();
        Class<?> originType=Class.forName("net.fabricmc.loader.api.metadata.ModOrigin",true,cl);
        for(Object mod:(Collection<?>)loader.getMethod("getAllMods").invoke(instance)) {
            Object meta=container.getMethod("getMetadata").invoke(mod);
            String id=String.valueOf(metadata.getMethod("getId").invoke(meta));
            rows.add(id+"|"+metadata.getMethod("getVersion").invoke(meta));
            Object origin=container.getMethod("getOrigin").invoke(mod);
            String kind=String.valueOf(originType.getMethod("getKind").invoke(origin));
            String source;
            if(kind.equals("NESTED"))source=originType.getMethod("getParentModId").invoke(origin)+"!"+originType.getMethod("getParentSubLocation").invoke(origin);
            else {
                List<String> paths=new ArrayList<>();
                for(Object path:(List<?>)originType.getMethod("getPaths").invoke(origin)) {
                    Path p=(Path)path;
                    if(Files.isRegularFile(p))paths.add(p.getFileName()+"@"+sha256(p));
                    else paths.add("<runtime-directory>");
                }
                source=String.join(";",paths);
            }
            origins.add(id+"|"+kind+"|"+source);
        }
        Collections.sort(rows);Files.write(evidence.resolve("loaded-mods.txt"),rows,StandardCharsets.UTF_8);
        Collections.sort(origins);Files.write(evidence.resolve("loaded-mod-origins.txt"),origins,StandardCharsets.UTF_8);
        Class<?> mixinExtras=Class.forName("com.llamalad7.mixinextras.MixinExtrasBootstrap",false,cl);
        String resource=String.valueOf(mixinExtras.getResource("MixinExtrasBootstrap.class"));
        Files.writeString(evidence.resolve("mixinextras-origin-private.txt"),resource,StandardCharsets.UTF_8);
        event("loaded_mods_recorded","records="+rows.size());
    }
    private static void recordAtlases(Object client,ClassLoader cl)throws Exception {
        Class<?> gl=Class.forName("org.lwjgl.opengl.GL11",true,cl),atlas=Class.forName("net.minecraft.class_1533",false,cl);
        Method getInteger=gl.getMethod("glGetInteger",int.class),bind=gl.getMethod("glBindTexture",int.class,int.class),getLevel=gl.getMethod("glGetTexLevelParameteri",int.class,int.class,int.class);
        int previous=((Number)getInteger.invoke(null,32873)).intValue();
        Object manager=method(client.getClass(),"method_5570").invoke(client);
        List<String> rectangles=new ArrayList<>();
        try {
            for(Object texture:((Map<?,?>)field(manager.getClass(),"field_6575").get(manager)).values()) {
                if(!atlas.isInstance(texture))continue;
                int id=field(texture.getClass(),"field_6544").getInt(texture);
                bind.invoke(null,3553,id);
                int width=((Number)getLevel.invoke(null,3553,0,4096)).intValue(),height=((Number)getLevel.invoke(null,3553,0,4097)).intValue();
                int textureType=((Number)method(texture.getClass(),"method_5826").invoke(texture)).intValue();
                Map<?,?> sprites=(Map<?,?>)field(texture.getClass(),"field_6559").get(texture);int outside=0,rotated=0;
                Set<String> uniqueRectangles=new HashSet<>();
                for(var entry:sprites.entrySet()) {
                    Object sprite=entry.getValue();int x=field(sprite.getClass(),"field_6563").getInt(sprite),y=field(sprite.getClass(),"field_6564").getInt(sprite),w=field(sprite.getClass(),"field_6565").getInt(sprite),h=field(sprite.getClass(),"field_6566").getInt(sprite);
                    boolean turn=field(sprite.getClass(),"field_6562").getBoolean(sprite);if(turn){int swap=w;w=h;h=swap;rotated++;}
                    if(x<0||y<0||w<=0||h<=0||x+w>width||y+h>height)outside++;
                    String rectangle=x+","+y+","+w+","+h;uniqueRectangles.add(rectangle);rectangles.add(textureType+"|"+entry.getKey()+"|"+rectangle+"|rotated="+turn);
                }
                event("native_atlas_observed","type="+textureType+";gpuSize="+width+"x"+height+";uploadedSprites="+sprites.size()+";uniqueRectangles="+uniqueRectangles.size()+";rotated="+rotated+";outsideBounds="+outside+";nativeStitchAndUploadCompleted=true");
                if(id<=0||width<=0||height<=0||outside>0)throw new IllegalStateException("Actual native atlas geometry mismatch");
            }
            Collections.sort(rectangles);Files.write(evidence.resolve("native-sprite-rectangles.tsv"),rectangles,StandardCharsets.UTF_8);
            event("native_gpu_backend",String.valueOf(gl.getMethod("glGetString",int.class).invoke(null,7936))+";"+gl.getMethod("glGetString",int.class).invoke(null,7937)+";"+gl.getMethod("glGetString",int.class).invoke(null,7938));
        } finally {bind.invoke(null,3553,previous);}
    }
    private static String sha256(Path p)throws Exception {
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
        try(InputStream input=Files.newInputStream(p)){byte[] bytes=new byte[65536];for(int n;(n=input.read(bytes))>=0;)digest.update(bytes,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
    private static Method method(Class<?> c,String name,Class<?>... args)throws Exception {
        for(Class<?> x=c;x!=null;x=x.getSuperclass())try{Method m=x.getDeclaredMethod(name,args);m.setAccessible(true);return m;}catch(NoSuchMethodException ignored){}
        throw new NoSuchMethodException(c.getName()+"."+name);
    }
    private static Field field(Class<?> c,String name)throws Exception {
        for(Class<?> x=c;x!=null;x=x.getSuperclass())try{Field f=x.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(c.getName()+"."+name);
    }
    private static String required(String k){String v=System.getProperty("btw.qa."+k);if(v==null||v.isBlank())throw new IllegalArgumentException(k);return v;}
    private static synchronized void event(String name,String detail)throws IOException {
        if(evidence!=null)Files.writeString(evidence.resolve("events.tsv"),Instant.now()+"\t"+name+"\t"+detail+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    private static void failure(Throwable t){failed=true;while(t instanceof InvocationTargetException&&t.getCause()!=null)t=t.getCause();try{if(originalNameRegistry!=null){replaceNameRegistry(originalNameRegistry);originalNameRegistry=null;}if(originalSkinRegistry!=null){replaceRegistry(originalSkinRegistry);originalSkinRegistry=null;}event("agent_failure",t.getClass().getName()+":"+String.valueOf(t.getMessage()));}catch(Exception ignored){}t.printStackTrace();}
}
