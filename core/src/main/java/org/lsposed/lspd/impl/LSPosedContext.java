package org.lsposed.lspd.impl;

import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.DeadSystemException;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.lsposed.lspd.core.ApplicationServiceClient;
import org.lsposed.lspd.core.BuildConfig;
import org.lsposed.lspd.impl.utils.LSPosedDexParser;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.nativebridge.HookBridge;
import org.lsposed.lspd.nativebridge.NativeAPI;
import org.lsposed.lspd.service.ILSPInjectedModuleService;
import org.lsposed.lspd.util.LspModuleClassLoader;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.errors.XposedFrameworkError;
import io.github.libxposed.api.utils.DexParser;


@SuppressLint("NewApi")
public class LSPosedContext implements XposedInterface {

    private static final String TAG = "LSPosedContext";

    public static boolean isSystemServer;
    public static String appDir;
    public static String processName;

    static final Set<XposedModule> modules = ConcurrentHashMap.newKeySet();

    private final String mPackageName;
    private final ApplicationInfo mApplicationInfo;
    private final ILSPInjectedModuleService service;
    private final ExceptionMode mDefaultExceptionMode;
    private final Map<String, SharedPreferences> mRemotePrefs = new ConcurrentHashMap<>();

    /**
     * Set while this generation is on its way out. A hook registered by retired code would outlive
     * the swap and keep the old classloader reachable through its hooker, which is the one thing a
     * reload exists to prevent, so registration is closed once the reload has been accepted.
     */
    private volatile boolean mFrozen = false;

    LSPosedContext(String packageName, ApplicationInfo applicationInfo, ILSPInjectedModuleService service,
                   ExceptionMode defaultExceptionMode) {
        this.mPackageName = packageName;
        this.mApplicationInfo = applicationInfo;
        this.service = service;
        this.mDefaultExceptionMode = defaultExceptionMode;
    }

    void freeze() {
        mFrozen = true;
    }

    void unfreeze() {
        mFrozen = false;
    }

    /**
     * Refuses registration once this generation has been accepted for retirement. Consulted both
     * when a hook builder is handed out and when it is used, because a builder handed out just
     * before the reload was accepted is still in the module's hands.
     */
    void checkNotFrozen() {
        if (mFrozen) {
            throw new IllegalStateException("Cannot register hooks from a retired module generation");
        }
    }

    // module lifecycle dispatch: fire every callback, modules react to what they override.

    public static void callOnModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        for (XposedModule module : modules) {
            try {
                module.onModuleLoaded(param);
            } catch (Throwable t) {
                Log.e(TAG, "Error when calling onModuleLoaded of " + module.getModuleApplicationInfo().packageName, t);
            }
        }
    }

    public static void callOnPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        for (XposedModule module : modules) {
            try {
                module.onPackageLoaded(param);
            } catch (Throwable t) {
                Log.e(TAG, "Error when calling onPackageLoaded of " + module.getModuleApplicationInfo().packageName, t);
            }
        }
    }

    public static void callOnPackageReady(XposedModuleInterface.PackageReadyParam param) {
        for (XposedModule module : modules) {
            try {
                module.onPackageReady(param);
            } catch (Throwable t) {
                Log.e(TAG, "Error when calling onPackageReady of " + module.getModuleApplicationInfo().packageName, t);
            }
        }
    }

    public static void callOnSystemServerLoaded(XposedModuleInterface.SystemServerLoadedParam param) {
        for (XposedModule module : modules) {
            try {
                module.onSystemServerLoaded(param);
            } catch (Throwable t) {
                Log.e(TAG, "Error when calling onSystemServerLoaded of " + module.getModuleApplicationInfo().packageName, t);
            }
        }
    }

    public static void callOnSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        for (XposedModule module : modules) {
            try {
                module.onSystemServerStarting(param);
            } catch (Throwable t) {
                Log.e(TAG, "Error when calling onSystemServerStarting of " + module.getModuleApplicationInfo().packageName, t);
            }
        }
    }

    /**
     * The entry classes of one module package as they are currently loaded, together with the
     * framework interface they were attached to.
     *
     * <p>A reload replaces this whole object. The new generation is built next to the old one and
     * only then takes its place, so no call ever sees the module half rebuilt.</p>
     */
    private static final class Generation {
        final ClassLoader classLoader;
        final LSPosedContext context;
        final List<XposedModule> entries;

        Generation(ClassLoader classLoader, LSPosedContext context, List<XposedModule> entries) {
            this.classLoader = classLoader;
            this.context = context;
            this.entries = entries;
        }
    }

    /** Live generations by module package name, so a reload can find what it has to retire. */
    private static final Map<String, Generation> generations = new ConcurrentHashMap<>();

    /** One lock per module, so reloads of the same module queue instead of interleaving. */
    private static final Map<String, Object> reloadLocks = new ConcurrentHashMap<>();

    private static final class ModuleLoadedParamImpl implements XposedModuleInterface.ModuleLoadedParam {
        @Override
        public boolean isSystemServer() {
            return LSPosedContext.isSystemServer;
        }

        @NonNull
        @Override
        public String getProcessName() {
            return LSPosedContext.processName;
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    public static boolean loadModule(ActivityThread at, Module module) {
        try {
            Log.d(TAG, "Loading module " + module.packageName);
            var initLoader = XposedModule.class.getClassLoader();
            var mcl = loadModuleApk(module, initLoader);
            if (mcl.loadClass(XposedModule.class.getName()).getClassLoader() != initLoader) {
                Log.e(TAG, "  Cannot load module: " + module.packageName);
                Log.e(TAG, "  The Xposed API classes are compiled into the module's APK.");
                Log.e(TAG, "  This may cause strange issues and must be fixed by the module developer.");
                return false;
            }
            module.file.moduleLibraryNames.forEach(NativeAPI::recordNativeEntrypoint);
            var generation = instantiate(module, mcl, true);
            generations.put(module.packageName, generation);
            Log.d(TAG, "Loaded module " + module.packageName + ": " + generation.context);
        } catch (Throwable e) {
            Log.d(TAG, "Loading module " + module.packageName, e);
            return false;
        }
        return true;
    }

    /**
     * Loads a module's code into the process.
     *
     * <p>A module that targets 102 or higher is built against a framework that no longer offers
     * the legacy API, so the loader stops resolving it: legacy state is global and static, which
     * means anything holding on to it outlives a reload in a way the framework cannot clean up.
     * The names to refuse are not the ones written in source whenever dex obfuscation is on, so
     * they are resolved through the same map the rest of the framework uses.</p>
     */
    private static ClassLoader loadModuleApk(Module module, ClassLoader initLoader) {
        var sb = new StringBuilder();
        var abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        for (String abi : abis) {
            sb.append(module.apkPath).append("!/lib/").append(abi).append(File.pathSeparator);
        }
        var librarySearchPath = sb.toString();
        var blockLegacyApi = module.file != null
                && module.file.targetApiVersion >= XposedInterface.API_102;
        return LspModuleClassLoader.loadApk(module.apkPath, module.file.preLoadedDexes,
                librarySearchPath, initLoader, blockLegacyApi);
    }

    /**
     * Builds a generation from a module's already-loaded code.
     *
     * @param firstLoad whether this is the initial load, which is the only time
     *                  {@link XposedModuleInterface#onModuleLoaded} is delivered: the interface
     *                  says a reload does not replay it, so new code hears about the swap through
     *                  {@link XposedModuleInterface#onHotReloaded} instead
     */
    private static Generation instantiate(Module module, ClassLoader mcl, boolean firstLoad) {
        var defaultExceptionMode = module.file.exceptionPassthrough ? ExceptionMode.PASSTHROUGH : ExceptionMode.PROTECTIVE;
        var ctx = new LSPosedContext(module.packageName, module.applicationInfo, module.service, defaultExceptionMode);
        var entries = new ArrayList<XposedModule>();
        for (var entry : module.file.moduleClassNames) {
            var moduleClass = mcl.loadClass(entry);
            Log.d(TAG, "  Loading class " + moduleClass);
            if (!XposedModule.class.isAssignableFrom(moduleClass)) {
                Log.e(TAG, "    This class doesn't implement any sub-interface of XposedModule, skipping it");
                continue;
            }
            try {
                entries.add(instantiateEntry(moduleClass, ctx, firstLoad));
            } catch (Throwable e) {
                Log.e(TAG, "    Failed to load class " + moduleClass, e);
            }
        }
        return new Generation(mcl, ctx, entries);
    }

    private static XposedModule instantiateEntry(Class<?> moduleClass, LSPosedContext ctx, boolean firstLoad)
            throws Throwable {
        XposedModule moduleContext;
        try {
            // API 100 modules take a (XposedInterface, ModuleLoadedParam) ctor, API 101 and 102
            // modules use no-arg + attachFramework. try API 100 first, fall back to the no-arg
            // one, decided per module so nobody has to configure anything.
            var moduleEntry = moduleClass.getConstructor(XposedInterface.class,
                    XposedModuleInterface.ModuleLoadedParam.class);
            moduleContext = (XposedModule) moduleEntry.newInstance(ctx, new ModuleLoadedParamImpl());
        } catch (NoSuchMethodException e) {
            var entry = (XposedModule) moduleClass.getConstructor().newInstance();
            // From 102 an entry can leave the lifecycle on its own, and only for itself: the
            // framework holds the reference, so it is the one that has to be able to drop it,
            // while a sibling entry of the same module keeps receiving its callbacks.
            entry.attachFramework(ctx, () -> modules.remove(entry));
            moduleContext = entry;
        }
        modules.add(moduleContext);
        // An entry that detached from its own constructor never asked for callbacks at all.
        if (firstLoad && modules.contains(moduleContext)) {
            try {
                moduleContext.onModuleLoaded(new ModuleLoadedParamImpl());
            } catch (Throwable t) {
                // It is still subscribed, and it is not going to be part of any generation, so
                // nothing would ever take it out again.
                modules.remove(moduleContext);
                throw t;
            }
        }
        return moduleContext;
    }

    /**
     * Reloads a module into this process (API 102).
     *
     * <p>The old generation is asked first and nothing is disturbed until it agrees. Then its hook
     * registration is closed and its installed hooks are collected, and only after that is the new
     * generation built from the module's current APK - by which point the daemon has already
     * re-read it. The two generations are never both able to answer a call: the swap happens once
     * the new one is complete, and the handles the old one left are handed to the new code to
     * retire or take over.</p>
     *
     * <p>Reloads are serialised per module, so two updates arriving together cannot both freeze the
     * same generation and then race to replace it.</p>
     *
     * @param packageName the module to reload
     * @param extras      what the caller passed along, or {@code null}
     * @return whether the module was reloaded
     */
    public static boolean requestHotReload(String packageName, Bundle extras) {
        synchronized (reloadLocks.computeIfAbsent(packageName, k -> new Object())) {
            return reloadLocked(packageName, extras);
        }
    }

    private static boolean reloadLocked(String packageName, Bundle extras) {
        var previous = generations.get(packageName);
        if (previous == null) {
            Log.d(TAG, "Hot reload of " + packageName + " requested, but it is not loaded here");
            return false;
        }

        var reloading = new HotReloadingParamImpl(extras, previous.classLoader);
        var asked = false;
        for (var entry : previous.entries) {
            // An entry that detached asked to be left out of every lifecycle callback, and being
            // asked about a reload is one.
            if (!modules.contains(entry)) continue;
            asked = true;
            boolean accepted;
            try {
                accepted = entry.onHotReloading(reloading);
            } catch (Throwable t) {
                Log.e(TAG, "Error when calling onHotReloading of " + packageName, t);
                return false;
            }
            if (!accepted) {
                Log.d(TAG, "Hot reload of " + packageName + " refused");
                return false;
            }
        }
        if (!asked) {
            // Nothing is left that could agree, and an unanswered question is not a yes.
            Log.d(TAG, "Hot reload of " + packageName + " has no entry left to ask");
            return false;
        }

        // Close registration before the handle list is read: a hook old code registered from here
        // on would survive the swap and hold the retired classloader in place through its hooker.
        previous.context.freeze();
        var oldHandles = LSPosedBridge.HookRegistry.liveHandles(packageName);

        var swapped = false;
        try {
            var module = findRefreshedModule(packageName);
            if (module == null) {
                Log.e(TAG, "Hot reload of " + packageName + " found no module to load");
                return false;
            }
            var mcl = loadModuleApk(module, XposedModule.class.getClassLoader());
            // The new entries are subscribed as they are built, so an entry that detaches while it
            // is still being constructed lands here already out of the callback set.
            var next = instantiate(module, mcl, false);
            // The library names are re-recorded because an updated module may ship new ones; the
            // old entry points keep working until the process ends.
            module.file.moduleLibraryNames.forEach(NativeAPI::recordNativeEntrypoint);

            modules.removeAll(previous.entries);
            generations.put(packageName, next);
            swapped = true;

            var reloaded = new HotReloadedParamImpl(extras, reloading.savedInstanceState, oldHandles);
            for (var entry : next.entries) {
                if (!modules.contains(entry)) continue;
                try {
                    entry.onHotReloaded(reloaded);
                } catch (Throwable t) {
                    Log.e(TAG, "Error when calling onHotReloaded of " + packageName, t);
                }
            }
            Log.i(TAG, "Hot reloaded module " + packageName);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hot reload " + packageName, t);
            return false;
        } finally {
            // A reload that did not go through leaves the old code in charge, so it gets its
            // registration back; a completed one stays retired for good.
            if (!swapped) previous.context.unfreeze();
        }
    }

    private static Module findRefreshedModule(String packageName) {
        var client = ApplicationServiceClient.serviceClient;
        if (client == null) return null;
        for (var module : client.getModulesList()) {
            if (packageName.equals(module.packageName)) return module;
        }
        return null;
    }

    private static final class HotReloadingParamImpl implements XposedModuleInterface.HotReloadingParam {
        private final Bundle extras;
        private final ClassLoader retiredLoader;
        private Object savedInstanceState;

        HotReloadingParamImpl(Bundle extras, ClassLoader retiredLoader) {
            this.extras = extras;
            this.retiredLoader = retiredLoader;
        }

        @Override
        public Bundle getExtras() {
            return extras;
        }

        @Override
        public void setSavedInstanceState(Object outState) {
            if (isFromRetiredLoader(outState)) {
                throw new IllegalArgumentException(
                        "Saved state must not hold objects created by the module classloader being retired");
            }
            this.savedInstanceState = outState;
        }

        /**
         * Shallow test for whether an object was created by the generation being retired, or by a
         * loader derived from it. It looks at the object itself and nothing it refers to, so it is
         * a diagnostic rather than a guarantee: an object that slips through undetected is still a
         * module lifecycle bug.
         */
        private boolean isFromRetiredLoader(Object object) {
            if (object == null) return false;
            for (var cl = object.getClass().getClassLoader(); cl != null; cl = cl.getParent()) {
                if (cl == retiredLoader) return true;
            }
            return false;
        }
    }

    private static final class HotReloadedParamImpl implements XposedModuleInterface.HotReloadedParam {
        private final Bundle extras;
        private final Object savedInstanceState;
        private final List<XposedInterface.HookHandle> oldHookHandles;

        HotReloadedParamImpl(Bundle extras, Object savedInstanceState, List<XposedInterface.HookHandle> oldHookHandles) {
            this.extras = extras;
            this.savedInstanceState = savedInstanceState;
            this.oldHookHandles = oldHookHandles;
        }

        @Override
        public boolean isSystemServer() {
            return LSPosedContext.isSystemServer;
        }

        @NonNull
        @Override
        public String getProcessName() {
            return LSPosedContext.processName;
        }

        @Override
        public Bundle getExtras() {
            return extras;
        }

        @Override
        public Object getSavedInstanceState() {
            return savedInstanceState;
        }

        @NonNull
        @Override
        public List<XposedInterface.HookHandle> getOldHookHandles() {
            return oldHookHandles;
        }
    }

    @NonNull
    @Override
    public String getFrameworkName() {
        return BuildConfig.FRAMEWORK_NAME;
    }

    @NonNull
    @Override
    public String getFrameworkVersion() {
        return BuildConfig.VERSION_NAME;
    }

    @Override
    public long getFrameworkVersionCode() {
        return BuildConfig.VERSION_CODE;
    }

    @Override
    public long getFrameworkProperties() {
        try {
            return service.getFrameworkProperties();
        } catch (RemoteException e) {
            throw new XposedFrameworkError(e);
        }
    }

    @Override
    public int getFrameworkPrivilege() {
        try {
            return service.getFrameworkPrivilege();
        } catch (RemoteException ignored) {
            return -1;
        }
    }

    // Hooking (API 101)

    @Override
    @NonNull
    public HookBuilder hook(@NonNull Executable origin) {
        checkNotFrozen();
        return LSPosedBridge.newHookBuilder(this, origin, mPackageName, mDefaultExceptionMode);
    }

    @Override
    @NonNull
    public HookBuilder hookClassInitializer(@NonNull Class<?> origin) {
        checkNotFrozen();
        return LSPosedBridge.newClassInitializerHookBuilder(this, origin, mPackageName, mDefaultExceptionMode);
    }

    @Override
    public boolean deoptimize(@NonNull Executable executable) {
        return LSPosedBridge.doDeoptimize(executable);
    }

    @NonNull
    @Override
    public Invoker<?, Method> getInvoker(@NonNull Method method) {
        return LSPosedBridge.newInvoker(method);
    }

    @NonNull
    @Override
    public <T> CtorInvoker<T> getInvoker(@NonNull Constructor<T> constructor) {
        return LSPosedBridge.newInvoker(constructor);
    }

    // Hooking (API 100)

    @Override
    @NonNull
    public MethodUnhooker<Method> hook(@NonNull Method origin, @NonNull Class<? extends Hooker> hooker) {
        return LSPosedBridge.doHook(origin, PRIORITY_DEFAULT, hooker);
    }

    @Override
    @NonNull
    public MethodUnhooker<Method> hook(@NonNull Method origin, int priority, @NonNull Class<? extends Hooker> hooker) {
        return LSPosedBridge.doHook(origin, priority, hooker);
    }

    @Override
    @NonNull
    public <T> MethodUnhooker<Constructor<T>> hook(@NonNull Constructor<T> origin, @NonNull Class<? extends Hooker> hooker) {
        return LSPosedBridge.doHook(origin, PRIORITY_DEFAULT, hooker);
    }

    @Override
    @NonNull
    public <T> MethodUnhooker<Constructor<T>> hook(@NonNull Constructor<T> origin, int priority, @NonNull Class<? extends Hooker> hooker) {
        return LSPosedBridge.doHook(origin, priority, hooker);
    }

    private static boolean doDeoptimize(@NonNull Executable method) {
        if (Modifier.isAbstract(method.getModifiers())) {
            throw new IllegalArgumentException("Cannot deoptimize abstract methods: " + method);
        } else if (Proxy.isProxyClass(method.getDeclaringClass())) {
            throw new IllegalArgumentException("Cannot deoptimize methods from proxy class: " + method);
        }
        return HookBridge.deoptimizeMethod(method);
    }

    @Override
    public boolean deoptimize(@NonNull Method method) {
        return doDeoptimize(method);
    }

    @Override
    public <T> boolean deoptimize(@NonNull Constructor<T> constructor) {
        return doDeoptimize(constructor);
    }

    @NonNull
    @Override
    public <T> MethodUnhooker<Constructor<T>> hookClassInitializer(@NonNull Class<T> origin, @NonNull Class<? extends Hooker> hooker) {
        return LSPosedBridge.doHookClassInitializer(origin, PRIORITY_DEFAULT, hooker);
    }

    @NonNull
    @Override
    public <T> MethodUnhooker<Constructor<T>> hookClassInitializer(@NonNull Class<T> origin, int priority, @NonNull Class<? extends Hooker> hooker) {
        return LSPosedBridge.doHookClassInitializer(origin, priority, hooker);
    }

    @Nullable
    @Override
    public Object invokeOrigin(@NonNull Method method, @Nullable Object thisObject, Object[] args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
        return HookBridge.invokeOriginalMethod(method, thisObject, args);
    }

    @Override
    public <T> void invokeOrigin(@NonNull Constructor<T> constructor, @NonNull T thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
        HookBridge.invokeOriginalMethod(constructor, thisObject, args);
    }

    @Nullable
    @Override
    public Object invokeSpecial(@NonNull Method method, @NonNull Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
        if (Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException("Cannot invoke special on static method: " + method);
        }
        try {
            return HookBridge.invokeSpecialMethod(method, thisObject, args);
        } catch (InstantiationException e) {
            throw new InstantiationError(e.getMessage());
        }
    }

    @Override
    public <T> void invokeSpecial(@NonNull Constructor<T> constructor, @NonNull T thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
        if (Modifier.isStatic(constructor.getModifiers())) {
            throw new IllegalArgumentException("Cannot invoke special on static constructor: " + constructor);
        }
        try {
            HookBridge.invokeSpecialMethod(constructor, thisObject, args);
        } catch (InstantiationException e) {
            throw new InstantiationError(e.getMessage());
        }
    }

    @NonNull
    @Override
    public <T> T newInstanceOrigin(@NonNull Constructor<T> constructor, Object... args) throws InvocationTargetException, IllegalAccessException, InstantiationException {
        return (T) HookBridge.invokeOriginalMethod(constructor, null, args);
    }

    @NonNull
    @Override
    public <T, U> U newInstanceSpecial(@NonNull Constructor<T> constructor, @NonNull Class<U> subClass, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException {
        var superClass = constructor.getDeclaringClass();
        if (!superClass.isAssignableFrom(subClass)) {
            throw new IllegalArgumentException(subClass + " is not inherited from " + superClass);
        }
        return (U) HookBridge.invokeSpecialMethod(constructor, subClass, null, args);
    }

    @Override
    public void log(int priority, @Nullable String tag, @NonNull String msg) {
        log(priority, tag, msg, null);
    }

    @Override
    public void log(int priority, @Nullable String tag, @NonNull String message, @Nullable Throwable throwable) {
        if (message.isEmpty() && throwable == null) {
            return;
        }

        var estimatedLength = Math.max(0xFC2 - (tag == null ? 0 : tag.length()), 100);
        var output = new StringWriter(estimatedLength);
        var writer = new PrintWriter(output);

        var moduleTag = String.valueOf(tag);
        writer.println(String.format("[%s,%s] %s", mPackageName, moduleTag, message));

        if (throwable != null) {
            Throwable candidate;
            for (candidate = throwable; candidate != null && !(candidate instanceof UnknownHostException); candidate = candidate.getCause()) {
                if (candidate instanceof DeadSystemException) {
                    writer.println("DeadSystemException: The system died; earlier logs will point to the root cause");
                    break;
                }
            }
            if (candidate == null) {
                throwable.printStackTrace(writer);
            }
        }

        writer.flush();
        Log.println(priority, TAG, output.toString());
    }

    @Override
    @Deprecated
    public void log(@NonNull String message) {
        log(Log.INFO, "null", message, null);
    }

    @Override
    @Deprecated
    public void log(@NonNull String message, @NonNull Throwable throwable) {
        log(Log.ERROR, "null", message, throwable);
    }

    @Override
    public DexParser parseDex(@NonNull ByteBuffer dexData, boolean includeAnnotations) throws IOException {
        return new LSPosedDexParser(dexData, includeAnnotations);
    }

    @NonNull
    @Override
    public ApplicationInfo getModuleApplicationInfo() {
        return mApplicationInfo;
    }

    @NonNull
    @Override
    public ApplicationInfo getApplicationInfo() {
        return mApplicationInfo;
    }

    @NonNull
    @Override
    public SharedPreferences getRemotePreferences(String name) {
        if (name == null) throw new IllegalArgumentException("name must not be null");
        return mRemotePrefs.computeIfAbsent(name, n -> {
            try {
                return new LSPosedRemotePreferences(service, n);
            } catch (RemoteException e) {
                log(Log.ERROR, "null", "Failed to get remote preferences", e);
                throw new XposedFrameworkError(e);
            }
        });
    }

    @NonNull
    @Override
    public String[] listRemoteFiles() {
        try {
            return service.getRemoteFileList();
        } catch (RemoteException e) {
            log(Log.ERROR, "null", "Failed to list remote files", e);
            throw new XposedFrameworkError(e);
        }
    }

    @NonNull
    @Override
    public ParcelFileDescriptor openRemoteFile(String name) throws FileNotFoundException {
        if (name == null) throw new IllegalArgumentException("name must not be null");
        try {
            return service.openRemoteFile(name);
        } catch (RemoteException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }
}