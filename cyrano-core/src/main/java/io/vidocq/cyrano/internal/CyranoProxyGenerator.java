/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.cyrano.internal;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.MTD_void;

/**
 * Générateur de proxy via la Class-File API du JDK 25 (JEP 484) — produit une classe
 * concrète nommée {@code Cyrano$<SimpleName>} qui implémente l'interface client.
 *
 * <p>Pas de {@link java.lang.reflect.Proxy} dynamique, pas d'ASM ni de Byte Buddy.
 * La classe générée est chargée via {@link MethodHandles.Lookup#defineClass(byte[])}
 * dans le module {@code cyrano-core}. Avantages : compatible AOT (GraalVM, Leyden CDS),
 * stack traces lisibles, pas de {@code setAccessible(true)}.</p>
 *
 * <p>Anatomie d'un proxy généré pour une interface {@code com.acme.UserService} :</p>
 * <pre>
 *   package io.vidocq.cyrano.internal;            // package du Lookup (interne)
 *   final class Cyrano$UserService implements com.acme.UserService {
 *       private final CyranoInvocationHandler handler;
 *       Cyrano$UserService(CyranoInvocationHandler h) { this.handler = h; }
 *       public String getUser(long id) {
 *           Object[] args = new Object[] { Long.valueOf(id) };
 *           try {
 *               return (String) handler.invoke(this, 0, args);
 *           } catch (RuntimeException | Error e) { throw e; }
 *             catch (Exception e) { throw new RuntimeException(e); }
 *       }
 *       // … une méthode par entrée du Map&lt;Method, RequestSpec&gt;
 *   }
 * </pre>
 *
 * <p>L'index passé à {@link CyranoInvocationHandler#invoke(int, Object[])} correspond à
 * l'ordre d'itération de {@code Map<Method, RequestSpec>} (qui est un {@link
 * java.util.LinkedHashMap LinkedHashMap} — ordre d'insertion stable).</p>
 */
public final class CyranoProxyGenerator {

    private static final ClassDesc CD_HANDLER =
            CyranoInvocationHandler.class.describeConstable().orElseThrow();
    private static final ClassDesc CD_RuntimeException =
            RuntimeException.class.describeConstable().orElseThrow();
    private static final ClassDesc CD_Error = Error.class.describeConstable().orElseThrow();
    private static final ClassDesc CD_Exception = Exception.class.describeConstable().orElseThrow();
    private static final ClassDesc CD_Throwable = Throwable.class.describeConstable().orElseThrow();
    private static final ClassDesc CD_Closeable =
            java.io.Closeable.class.describeConstable().orElseThrow();

    private CyranoProxyGenerator() {
        // utility
    }

    /**
     * Génère une classe proxy implémentant {@code iface}, l'enregistre dans le ClassLoader
     * via {@link MethodHandles.Lookup#defineClass(byte[])} et la renvoie.
     *
     * <p>Idempotent au niveau du contenu — l'appelant doit cacher le résultat
     * (voir {@code CyranoProxyCache}).</p>
     */
    public static Class<?> generate(Class<?> iface, Map<Method, RequestSpec> specs) {
        List<Method> methods = new ArrayList<>(specs.keySet());
        String simpleName = "Cyrano$" + iface.getSimpleName();
        // Le proxy est généré dans le même package que l'interface cible afin de pouvoir
        // l'implémenter même quand elle est package-private, et pour éviter les soucis
        // de visibilité entre modules nommés (chaque module reste responsable de ses
        // proxies). Nécessite que le module utilisateur ouvre son package à cyrano-core
        // (ou que les deux soient dans le module unnamed, cas test/classpath).
        String pkg = iface.getPackageName();
        String binaryName = pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
        ClassDesc thisClass = ClassDesc.of(binaryName);
        ClassDesc ifaceDesc = iface.describeConstable().orElseThrow();

        byte[] bytes = ClassFile.of().build(thisClass, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
            // Always implement Closeable — spec MP Rest Client §8.1: all proxies must be Closeable
            cb.withInterfaceSymbols(ifaceDesc, CD_Closeable);
            cb.withSuperclass(CD_Object);
            cb.withField("handler", CD_HANDLER, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);

            // Constructeur : Cyrano$X(CyranoInvocationHandler h)
            cb.withMethodBody("<init>",
                    MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_void, CD_HANDLER),
                    ClassFile.ACC_PUBLIC,
                    code -> code
                            .aload(0)
                            .invokespecial(CD_Object, "<init>", MTD_void)
                            .aload(0)
                            .aload(1)
                            .putfield(thisClass, "handler", CD_HANDLER)
                            .return_());

            // Une méthode par entrée du scan, dans l'ordre d'insertion.
            for (int i = 0; i < methods.size(); i++) {
                Method m = methods.get(i);
                emitMethod(cb, thisClass, m, i);
            }

            // close() — spec §8.1 : tous les proxies sont Closeable.
            // Appelle handler.markClosed() ; après fermeture, invoke() lève IllegalStateException.
            cb.withMethodBody("close",
                    MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_void),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL,
                    code -> code
                            .aload(0)
                            .getfield(thisClass, "handler", CD_HANDLER)
                            .invokevirtual(CD_HANDLER, "markClosed", MTD_void)
                            .return_());
        });

        try {
            // privateLookupIn donne accès au package de l'interface — permet de définir
            // la classe proxy dans ce package, qu'il soit public ou non. Pour les
            // interfaces de modules nommés, le module utilisateur doit ouvrir son
            // package à cyrano-core via `opens` (sera documenté pour M3 CDI).
            MethodHandles.Lookup target = MethodHandles.privateLookupIn(iface, MethodHandles.lookup());
            return target.defineClass(bytes);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Impossible de définir le proxy " + binaryName
                            + " — assurez-vous que le module hôte ouvre son package à "
                            + "'io.vidocq.cyrano.core' (opens "
                            + iface.getPackageName() + " to io.vidocq.cyrano.core).", e);
        }
    }

    /**
     * Instancie un proxy déjà généré via le constructeur {@code (CyranoInvocationHandler)}.
     */
    @SuppressWarnings("unchecked")
    public static <T> T instantiate(Class<?> proxyClass, CyranoInvocationHandler handler) {
        try {
            var ctor = proxyClass.getDeclaredConstructor(CyranoInvocationHandler.class);
            return (T) ctor.newInstance(handler);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Échec d'instanciation du proxy " + proxyClass, e);
        }
    }

    private static void emitMethod(java.lang.classfile.ClassBuilder cb, ClassDesc thisClass,
                                   Method m, int methodIndex) {
        ClassDesc retDesc = m.getReturnType().describeConstable().orElseThrow();
        Class<?>[] paramTypes = m.getParameterTypes();
        ClassDesc[] paramDescs = new ClassDesc[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            paramDescs[i] = paramTypes[i].describeConstable().orElseThrow();
        }
        MethodTypeDesc methodType = MethodTypeDesc.of(retDesc, paramDescs);

        cb.withMethodBody(m.getName(), methodType,
                ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL,
                code -> {
                    // Compute slot of the Object[] local variable.
                    // Local slots : 0 = this, puis chaque paramètre selon sa taille.
                    int argsSlot = 1;
                    for (Class<?> p : paramTypes) {
                        argsSlot += (p == long.class || p == double.class) ? 2 : 1;
                    }

                    // Object[] args = new Object[paramTypes.length];
                    code.loadConstant(paramTypes.length);
                    code.anewarray(CD_Object);
                    int slot = 1;
                    for (int i = 0; i < paramTypes.length; i++) {
                        code.dup();
                        code.loadConstant(i);
                        Class<?> p = paramTypes[i];
                        loadAndBox(code, p, slot);
                        code.aastore();
                        slot += (p == long.class || p == double.class) ? 2 : 1;
                    }
                    code.astore(argsSlot);

                    // Bloc try { return (T) handler.invoke(methodIndex, args); }
                    var tryStart = code.newLabel();
                    var tryEnd = code.newLabel();
                    var catchRtEx = code.newLabel();
                    var catchError = code.newLabel();
                    var catchOther = code.newLabel();

                    code.labelBinding(tryStart);
                    code.aload(0);
                    code.getfield(thisClass, "handler", CD_HANDLER);
                    code.aload(0);
                    code.loadConstant(methodIndex);
                    code.aload(argsSlot);
                    code.invokevirtual(CD_HANDLER, "invoke",
                            MethodTypeDesc.of(CD_Object, CD_Object, CD_int,
                                    CD_Object.arrayType()));
                    emitReturn(code, m.getReturnType(), retDesc);
                    code.labelBinding(tryEnd);

                    // catch (RuntimeException re) { throw re; }
                    code.labelBinding(catchRtEx);
                    code.athrow();
                    // catch (Error err) { throw err; }
                    code.labelBinding(catchError);
                    code.athrow();
                    // catch (Exception ex) { throw new RuntimeException(ex); }
                    code.labelBinding(catchOther);
                    code.astore(argsSlot + 1);
                    code.new_(CD_RuntimeException);
                    code.dup();
                    code.aload(argsSlot + 1);
                    code.invokespecial(CD_RuntimeException, "<init>",
                            MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_void, CD_Throwable));
                    code.athrow();

                    code.exceptionCatch(tryStart, tryEnd, catchRtEx, CD_RuntimeException);
                    code.exceptionCatch(tryStart, tryEnd, catchError, CD_Error);
                    code.exceptionCatch(tryStart, tryEnd, catchOther, CD_Exception);
                });
    }

    /**
     * Charge le paramètre de slot {@code slot} sur la pile et le box si primitif,
     * pour pouvoir le stocker dans {@code Object[]}.
     */
    private static void loadAndBox(java.lang.classfile.CodeBuilder code, Class<?> type, int slot) {
        if (!type.isPrimitive()) {
            code.aload(slot);
            return;
        }
        if (type == long.class) {
            code.lload(slot);
            code.invokestatic(ClassDesc.of("java.lang.Long"), "valueOf",
                    MethodTypeDesc.of(ClassDesc.of("java.lang.Long"), java.lang.constant.ConstantDescs.CD_long));
        } else if (type == double.class) {
            code.dload(slot);
            code.invokestatic(ClassDesc.of("java.lang.Double"), "valueOf",
                    MethodTypeDesc.of(ClassDesc.of("java.lang.Double"), java.lang.constant.ConstantDescs.CD_double));
        } else if (type == float.class) {
            code.fload(slot);
            code.invokestatic(ClassDesc.of("java.lang.Float"), "valueOf",
                    MethodTypeDesc.of(ClassDesc.of("java.lang.Float"), java.lang.constant.ConstantDescs.CD_float));
        } else if (type == boolean.class) {
            code.iload(slot);
            code.invokestatic(ClassDesc.of("java.lang.Boolean"), "valueOf",
                    MethodTypeDesc.of(ClassDesc.of("java.lang.Boolean"), java.lang.constant.ConstantDescs.CD_boolean));
        } else {
            // int, short, byte, char — chargés via iload puis boxés
            code.iload(slot);
            ClassDesc wrapper;
            ClassDesc prim;
            if (type == int.class) {
                wrapper = ClassDesc.of("java.lang.Integer");
                prim = java.lang.constant.ConstantDescs.CD_int;
            } else if (type == short.class) {
                wrapper = ClassDesc.of("java.lang.Short");
                prim = java.lang.constant.ConstantDescs.CD_short;
            } else if (type == byte.class) {
                wrapper = ClassDesc.of("java.lang.Byte");
                prim = java.lang.constant.ConstantDescs.CD_byte;
            } else if (type == char.class) {
                wrapper = ClassDesc.of("java.lang.Character");
                prim = java.lang.constant.ConstantDescs.CD_char;
            } else {
                throw new IllegalStateException("Type primitif inconnu : " + type);
            }
            code.invokestatic(wrapper, "valueOf", MethodTypeDesc.of(wrapper, prim));
        }
    }

    /**
     * Émet le code de retour selon le type Java de la méthode :
     * cast (si nécessaire) puis areturn/ireturn/lreturn/dreturn/freturn/return.
     */
    private static void emitReturn(java.lang.classfile.CodeBuilder code, Class<?> retType, ClassDesc retDesc) {
        if (retType == void.class) {
            code.pop();
            code.return_();
            return;
        }
        if (retType.isPrimitive()) {
            // Unbox depuis Object retourné par handler.invoke
            ClassDesc wrapper;
            String unboxName;
            ClassDesc prim;
            if (retType == int.class) {
                wrapper = ClassDesc.of("java.lang.Integer"); unboxName = "intValue"; prim = java.lang.constant.ConstantDescs.CD_int;
            } else if (retType == long.class) {
                wrapper = ClassDesc.of("java.lang.Long"); unboxName = "longValue"; prim = java.lang.constant.ConstantDescs.CD_long;
            } else if (retType == double.class) {
                wrapper = ClassDesc.of("java.lang.Double"); unboxName = "doubleValue"; prim = java.lang.constant.ConstantDescs.CD_double;
            } else if (retType == float.class) {
                wrapper = ClassDesc.of("java.lang.Float"); unboxName = "floatValue"; prim = java.lang.constant.ConstantDescs.CD_float;
            } else if (retType == short.class) {
                wrapper = ClassDesc.of("java.lang.Short"); unboxName = "shortValue"; prim = java.lang.constant.ConstantDescs.CD_short;
            } else if (retType == byte.class) {
                wrapper = ClassDesc.of("java.lang.Byte"); unboxName = "byteValue"; prim = java.lang.constant.ConstantDescs.CD_byte;
            } else if (retType == char.class) {
                wrapper = ClassDesc.of("java.lang.Character"); unboxName = "charValue"; prim = java.lang.constant.ConstantDescs.CD_char;
            } else if (retType == boolean.class) {
                wrapper = ClassDesc.of("java.lang.Boolean"); unboxName = "booleanValue"; prim = java.lang.constant.ConstantDescs.CD_boolean;
            } else {
                throw new IllegalStateException("Type retour primitif inconnu : " + retType);
            }
            code.checkcast(wrapper);
            code.invokevirtual(wrapper, unboxName, MethodTypeDesc.of(prim));
            if (retType == long.class) code.lreturn();
            else if (retType == double.class) code.dreturn();
            else if (retType == float.class) code.freturn();
            else code.ireturn(); // int, short, byte, char, boolean
            return;
        }
        // Référence
        code.checkcast(retDesc);
        code.areturn();
    }
}




