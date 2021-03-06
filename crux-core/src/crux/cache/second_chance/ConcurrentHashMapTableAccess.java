package crux.cache.second_chance;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import java.util.concurrent.ConcurrentHashMap;

public final class ConcurrentHashMapTableAccess {
    private static final MethodHandle TABLE_FIELD_METHOD_HANDLE;

    static {
        try {
            try {
                Method getModule = Class.class.getDeclaredMethod("getModule");
                Class<?> moduleClass = getModule.getReturnType();
                Method isNamed = moduleClass.getDeclaredMethod("isNamed");
                Method addOpens = moduleClass.getDeclaredMethod("addOpens", String.class, moduleClass);

                Object thisModule = getModule.invoke(ConcurrentHashMapTableAccess.class);
                if (!(boolean) isNamed.invoke(thisModule)) {
                    Object javaBaseModule = getModule.invoke(ConcurrentHashMap.class);
                    addOpens.invoke(javaBaseModule, ConcurrentHashMap.class.getPackage().getName(), thisModule);
                }
            } catch (Exception ignore) {
            }

            Field tableField = ConcurrentHashMap.class.getDeclaredField("table");
            tableField.setAccessible(true);
            MethodHandle mh = MethodHandles.lookup().unreflectGetter(tableField);
            TABLE_FIELD_METHOD_HANDLE = mh.asType(mh.type().changeReturnType(Object[].class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static final <K, V> Object[] getConcurrentHashMapTable(final ConcurrentHashMap<K, V> map) throws Throwable {
        return (Object[]) TABLE_FIELD_METHOD_HANDLE.invokeExact(map);
    }
}
