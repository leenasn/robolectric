import org.gradle.api.Plugin
import org.gradle.api.Project
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

import java.lang.reflect.Modifier
import java.util.jar.JarEntry
import java.util.jar.JarInputStream

import static java.util.Arrays.asList

class CheckApiChangesPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
//        project.extensions.create("checkApiChanges", CheckApiChangesExtension)

        project.configurations {
            checkApiChanges
        }

//        project.afterEvaluate {
//            project.dependencies.checkApiChanges project.checkApiChanges.baseArtifact
//        }

        project.task('checkForApiChanges', dependsOn: 'jar') {
            doLast {

                def baseUrls = project.configurations.checkApiChanges*.toURI()*.toURL()
                println "${project.name}: checkForApiChanges: ${baseUrls}"
                Map<String, ClassMethod> prevClassMethods = findClassMethods(baseUrls)
                Map<String, ClassMethod> curClassMethods = findClassMethods(asList(new URL("file://${project.jar.archivePath}")))
                Set<String> allMethods = new TreeSet<>(prevClassMethods.keySet())
                allMethods.addAll(curClassMethods.keySet())
                String prevClassName = null
                for (String classMethodName : allMethods) {
                    ClassMethod prevClassMethod = prevClassMethods.get(classMethodName)
                    ClassMethod curClassMethod = curClassMethods.get(classMethodName)

                    def introClass = { classMethod ->
                        if (classMethod.className != prevClassName) {
                            prevClassName = classMethod.className
                            println "\n$prevClassName:"
                        }
                    }

                    if (prevClassMethod == null) {
                        // added
                        if (curClassMethod.visible) {
                            introClass(curClassMethod)
                            println "+\t$curClassMethod.methodDesc"
                        }
                    } else if (curClassMethod == null) {
                        // removed
                        if (prevClassMethod.visible && !prevClassMethod.deprecated) {
                            introClass(prevClassMethod)
                            println "-\t$prevClassMethod.methodDesc"
                        }
                    } else if (prevClassMethod != curClassMethod) {
                        // modified
                        if (prevClassMethod.visible || curClassMethod.visible) {
                            introClass(curClassMethod)
                            println "changed: $curClassMethod.methodDesc"
                        }
                    }
                }
            }
        }
    }

    private Map<String, ClassMethod> findClassMethods(List<URL> baseUrls) {
        Map<String, ClassMethod> classMethods = new HashMap<>()
        for (URL url : baseUrls) {
            if (url.protocol == 'file') {
                def file = new File(url.path)
                println "file = ${file}"
                def stream = new FileInputStream(file)
                def jarStream = new JarInputStream(stream)
                while (true) {
                    JarEntry entry = jarStream.nextJarEntry
                    if (entry == null) break

                    if (!entry.directory && entry.name.endsWith(".class")) {
                        def reader = new ClassReader(jarStream)
                        def node = new ClassNode()
                        reader.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES)
//                        println "node = $node.name"
                        for (MethodNode method : node.methods) {
                            def classMethod = new ClassMethod(node, method)
                            classMethods.put(classMethod.desc, classMethod)
                        }
                    }
                }
                stream.close()
            }
        }
        classMethods
    }

    static class ClassMethod {
        ClassNode classNode
        MethodNode methodNode

        ClassMethod(ClassNode classNode, MethodNode methodNode) {
            this.classNode = classNode
            this.methodNode = methodNode
        }

        boolean equals(o) {
            if (this.is(o)) return true
            if (getClass() != o.class) return false

            ClassMethod that = (ClassMethod) o

            if (classNode.name != that.classNode.name) return false
            if (methodNode.name != that.methodNode.name) return false
            if (methodNode.signature != that.methodNode.signature) return false
            if (methodNode.exceptions != that.methodNode.exceptions) return false
            if (methodNode.access != that.methodNode.access) return false

            return true
        }

        int hashCode() {
            int result
            result = (classNode.name != null ? classNode.name.hashCode() : 0)
            result = 31 * result + (methodNode.name != null ? methodNode.name.hashCode() : 0)
            result = 31 * result + (methodNode.signature != null ? methodNode.signature.hashCode() : 0)
            result = 31 * result + (methodNode.exceptions != null ? methodNode.exceptions.hashCode() : 0)
            result = 31 * result + methodNode.access
            return result
        }

        public String getDesc() {
            return "$className#$methodDesc"
        }

        private String getMethodDesc() {
            def args = new StringBuilder()
            def returnType = new StringBuilder()
            def buf = args

            int arrayDepth = 0
            def flushArrays = {
                for (; arrayDepth > 0; arrayDepth--) {
                    buf.append("[]")
                }
            }

            def write = { typeName ->
                if (buf.size() > 0) buf.append(", ")
                buf.append(typeName)
                flushArrays()
            }

            def chars = methodNode.desc.toCharArray()
            def i = 0

            def readObj = {
                if (buf.size() > 0) buf.append(", ")
                for (; i < chars.length; i++) {
                    char c = chars[i]
                    if (c == ';' as char) break
                    buf.append((c == '/' as char) ? '.' : c)
                }
                flushArrays()
            }

            for (; i < chars.length;) {
                def c = chars[i++]
                switch (c) {
                    case '(': break;
                    case ')': buf = returnType; break;
                    case '[': arrayDepth++; break;
                    case 'Z': write('boolean'); break;
                    case 'B': write('byte'); break;
                    case 'S': write('short'); break;
                    case 'I': write('int'); break;
                    case 'J': write('long'); break;
                    case 'F': write('float'); break;
                    case 'D': write('double'); break;
                    case 'C': write('char'); break;
                    case 'L': readObj(); break;
                    case 'V': write('void'); break;
                }
            }
            "${returnType.toString()} $methodNode.name(${args.toString()})"
        }

        @Override
        public String toString() {
            return className + "#$methodNode.desc";
        }

        private String getSignature() {
            methodNode.signature == null ? "()V" : methodNode.signature
        }

        private String getClassName() {
            classNode.name.replace('/', '.')
        }

        boolean isDeprecated() {
            for (AnnotationNode annotationNode : methodNode.visibleAnnotations) {
                if (annotationNode.desc == "Ljava/lang/Deprecated;") {
                    return true
                }
            }
            false
        }

        boolean isVisible() {
            (Modifier.isPublic(classNode.access) || Modifier.isProtected(classNode.access)) &&
                    (Modifier.isPublic(methodNode.access) || Modifier.isProtected(methodNode.access)) &&
                    !(classNode.name =~ /\$[0-9]/) && !(methodNode.name =~ /^access\$/)
        }
    }
}

class CheckApiChangesExtension {
    String baseArtifact
}