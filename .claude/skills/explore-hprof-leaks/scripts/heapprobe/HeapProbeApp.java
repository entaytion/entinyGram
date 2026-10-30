import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.mat.util.IProgressListener;
import org.eclipse.mat.util.VoidProgressListener;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.SnapshotFactory;
import org.eclipse.mat.snapshot.IPathsFromGCRootsComputer;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.NamedReference;

/**
 * Generic Eclipse MAT probe, packaged as an OSGi IApplication so it runs inside
 * MAT's equinox runtime (the parser plugins need a real OSGi context; running
 * SnapshotFactory from a plain `java -jar` fails with ParserPlugin.getDefault()==null).
 *
 * Why this exists: MAT's HTML "Leak Suspects / Details" renderer can crash
 * (AIOOBE in MultiplePath2GCRootsQuery) on corrupt strings, and the HTML reports
 * don't answer the questions that actually matter for multiply-held cohorts:
 * "who are ALL the strong holders", "is this path strong or does it die at a weak
 * ref", "how many of X are stuck as NotificationCenter observers". This does.
 *
 * Usage (see build_and_run.sh):
 *   heapprobe <dump.ec.hprof> <command> <args...>
 *
 * Commands:
 *   counts    <class> [<class>...]              instance count per class
 *   histogram <class> [<class>...]              inbound-referrer class histogram
 *   inbound   <class> [limit=6]                 per-instance inbound refs, sorted by retained
 *   paths     <class> [objs=3] [paths=4]        STRONG GC-root paths (weak/soft/phantom excluded)
 *   tally     <class> [kw1,kw2,...]             one shortest strong path per instance +
 *                                               first-strong-holder distribution
 *   observers <class>                           how many instances sit in NotificationCenter
 *                                               observer lists, grouped by eventId
 *   inspect   <class>                           first 5 instances: class hierarchy, GC-root
 *                                               type, retained size, outbound refs
 *   root      <0xADDR>                          GC-root info + inbound refs for one address
 */
public class HeapProbeApp implements IApplication {

    private static final IProgressListener PROGRESS = new VoidProgressListener();
    private ISnapshot snapshot;

    @Override
    public Object start(IApplicationContext ctx) throws Exception {
        String[] args = (String[]) ctx.getArguments().get("application.args");
        if (args == null || args.length < 2) {
            System.out.println("usage: heapprobe <dump> <command> <args...>");
            System.out.println("commands: counts|histogram|inbound|paths|tally|observers|inspect|root");
            return IApplication.EXIT_OK;
        }
        File dump = new File(args[0]);
        String cmd = args[1];
        String[] rest = Arrays.copyOfRange(args, 2, args.length);

        snapshot = SnapshotFactory.openSnapshot(dump, new HashMap<String, String>(), PROGRESS);
        System.out.println("PROBE heap=" + snapshot.getSnapshotInfo().getUsedHeapSize()
            + " objects=" + snapshot.getSnapshotInfo().getNumberOfObjects());

        try {
            switch (cmd) {
                case "counts":    for (String c : rest) counts(c); break;
                case "histogram": for (String c : rest) inboundHistogram(c); break;
                case "inbound":   inboundDetail(rest[0], rest.length > 1 ? Integer.parseInt(rest[1]) : 6); break;
                case "paths":     gcPaths(rest[0],
                                      rest.length > 1 ? Integer.parseInt(rest[1]) : 3,
                                      rest.length > 2 ? Integer.parseInt(rest[2]) : 4); break;
                case "tally":     tally(rest[0], rest.length > 1 ? rest[1] : ""); break;
                case "observers": countObservers(rest[0]); break;
                case "inspect": inspect(rest[0]); break;
                case "root": rootInfo(rest[0]); break;
                case "queue":     dumpQueues(); break;
                case "owners":    ownerSignatures(rest[0]); break;
                case "fields":    dumpFields(rest[0], rest.length > 1 ? Integer.parseInt(rest[1]) : 5,
                                      rest.length > 2 && rest[2].equals("inbound")); break;
                case "map":       dumpMap(rest[0], rest.length > 1 ? Integer.parseInt(rest[1]) : 40); break;
                case "at":        printFields("OBJECT", snapshot.mapAddressToId(Long.parseLong(rest[0].replace("0x", ""), 16))); break;
                case "str":       for (String a : rest) System.out.println(a + " = " + classSpecific(snapshot.getObject(snapshot.mapAddressToId(Long.parseLong(a.replace("0x", ""), 16))))); break;
                case "raw":       for (String a : rest) System.out.println(a + " = " + rawString(snapshot.getObject(snapshot.mapAddressToId(Long.parseLong(a.replace("0x", ""), 16))))); break;
                case "arr":       for (String a : rest) dumpArray(Long.parseLong(a.replace("0x", ""), 16)); break;
                default: System.out.println("unknown command: " + cmd);
            }
        } finally {
            snapshot.dispose();
        }
        return IApplication.EXIT_OK;
    }

    @Override
    public void stop() {}

    // ---- commands -----------------------------------------------------------

    private void rootInfo(String hexAddr) throws Exception {
        long addr = Long.parseLong(hexAddr.replace("0x", ""), 16);
        int id = snapshot.mapAddressToId(addr);
        IObject obj = snapshot.getObject(id);
        System.out.println("OBJECT @ 0x" + Long.toHexString(addr) + " class=" + obj.getClazz().getName() + " isGCRoot=" + snapshot.isGCRoot(id));
        if (snapshot.isGCRoot(id)) {
            for (org.eclipse.mat.snapshot.model.GCRootInfo root : snapshot.getGCRootInfo(id)) {
                System.out.println("  ROOT TYPE: " + org.eclipse.mat.snapshot.model.GCRootInfo.getTypeAsString(root.getType()));
                System.out.println("  CONTEXT ID: " + root.getContextId() + " CONTEXT ADDR: 0x" + Long.toHexString(root.getContextAddress()));
            }
        }
        System.out.println("INBOUND:");
        for (int inId : snapshot.getInboundRefererIds(id)) {
            IObject inObj = snapshot.getObject(inId);
            System.out.println("  <- 0x" + Long.toHexString(snapshot.mapIdToAddress(inId)) + " " + inObj.getTechnicalName() + " isRoot=" + snapshot.isGCRoot(inId));
        }
    }

    private void inspect(String className) throws Exception {
        int[] ids = idsFor(className);
        System.out.println("==== INSPECT " + className + " count=" + ids.length + " ====");
        int limit = Math.min(ids.length, 5);
        for (int i = 0; i < limit; i++) {
            int id = ids[i];
            IObject obj = snapshot.getObject(id);
            long addr = snapshot.mapIdToAddress(id);
            boolean isRoot = snapshot.isGCRoot(id);
            System.out.println("INSTANCE #" + i + " @ 0x" + Long.toHexString(addr) + " isGCRoot=" + isRoot + " retained=" + snapshot.getRetainedHeapSize(id));
            if (isRoot) {
                for (org.eclipse.mat.snapshot.model.GCRootInfo root : snapshot.getGCRootInfo(id)) {
                    System.out.println("  GC ROOT TYPE: " + org.eclipse.mat.snapshot.model.GCRootInfo.getTypeAsString(root.getType()));
                }
            }
            IClass cls = obj.getClazz();
            System.out.println("  class: " + cls.getName() + " super: " + (cls.getSuperClass() != null ? cls.getSuperClass().getName() : "none"));
            for (NamedReference ref : obj.getOutboundReferences()) {
                System.out.println("  outbound: " + ref.getName() + " -> " + (ref.getObject() != null ? ref.getObject().getTechnicalName() : "null"));
            }
        }
    }

    private void counts(String className) throws Exception {
        int[] ids = idsFor(className);
        long shallow = 0;
        for (int id : ids) shallow += snapshot.getHeapSize(id);
        System.out.println("\n==== COUNT " + className + " instances=" + ids.length
            + " shallowTotal=" + shallow + " ====");
    }

    private void inboundHistogram(String className) throws Exception {
        int[] ids = idsFor(className);
        System.out.println("\n==== INBOUND HISTOGRAM " + className + " count=" + ids.length + " ====");
        Map<String, int[]> hist = new HashMap<>();
        for (int id : ids)
            for (int from : snapshot.getInboundRefererIds(id))
                hist.computeIfAbsent(snapshot.getObject(from).getClazz().getName(), k -> new int[1])[0]++;
        hist.entrySet().stream()
            .sorted((a, b) -> b.getValue()[0] - a.getValue()[0]).limit(30)
            .forEach(e -> System.out.println("  " + e.getValue()[0] + "  " + e.getKey()));
    }

    private void inboundDetail(String className, int limit) throws Exception {
        int[] ids = sortedByRetained(idsFor(className));
        System.out.println("\n==== INBOUND DETAIL " + className + " ====");
        for (int i = 0; i < Math.min(ids.length, limit); i++) {
            int id = ids[i];
            int[] inbound = snapshot.getInboundRefererIds(id);
            System.out.println("OBJECT " + obj(id) + " retained=" + retained(id) + " inbound=" + inbound.length);
            Integer[] boxed = Arrays.stream(inbound).boxed().toArray(Integer[]::new);
            Arrays.sort(boxed, Comparator.comparingLong((Integer v) -> retained(v)).reversed());
            for (int from : boxed)
                System.out.println("    <- " + refsTo(from, id) + " " + obj(from) + " retained=" + retained(from));
        }
    }

    private void gcPaths(String className, int limitObjects, int limitPaths) throws Exception {
        int[] ids = sortedByRetained(idsFor(className));
        Map<IClass, Set<String>> exc = excludeMap();
        System.out.println("\n==== STRONG GC-ROOT PATHS " + className + " (weak/soft/phantom excluded) ====");
        for (int i = 0; i < Math.min(ids.length, limitObjects); i++) {
            int id = ids[i];
            System.out.println("OBJECT " + obj(id) + " retained=" + retained(id));
            IPathsFromGCRootsComputer c = snapshot.getPathsFromGCRoots(id, exc);
            for (int p = 0; p < limitPaths; p++) {
                int[] path = c.getNextShortestPath();
                if (path == null) break;
                System.out.println("  PATH" + p + " len=" + path.length);
                int show = Math.min(path.length, 16);
                for (int k = 0; k < show; k++) {
                    String edge = k + 1 < path.length ? refsTo(path[k + 1], path[k]) : "[GCROOT]";
                    System.out.println("      " + edge + " " + obj(path[k]) + " retained=" + retained(path[k]));
                }
            }
        }
    }

    /**
     * One shortest STRONG path per instance, classified by its "first strong holder":
     * the first class along the path (object -> root) that matches one of the given
     * keyword substrings. Pass the candidate holder names you're testing, comma-separated
     * (e.g. "ChatBackgroundDrawable,LaunchActivity,ReactionsEffectOverlay"). With no
     * keywords, falls back to the first app-package class that isn't the target or a
     * generic container. The tally reveals a multiply-held cohort's root distribution.
     */
    private void tally(String className, String keywordCsv) throws Exception {
        int[] ids = idsFor(className);
        String[] keywords = keywordCsv.isEmpty() ? new String[0] : keywordCsv.split(",");
        Map<IClass, Set<String>> exc = excludeMap();
        System.out.println("\n==== TALLY " + className + " (n=" + ids.length + ") keywords=" + keywordCsv + " ====");
        Map<String, int[]> hist = new HashMap<>();
        for (int id : ids) {
            IPathsFromGCRootsComputer c = snapshot.getPathsFromGCRoots(id, exc);
            int[] path = c.getNextShortestPath();
            StringBuilder sb = new StringBuilder();
            String key = "?";
            if (path != null) {
                for (int k = 1; k < path.length; k++) {
                    String cn = snapshot.getObject(path[k]).getClazz().getName();
                    String edge = k + 1 < path.length ? refsTo(path[k + 1], path[k]) : "";
                    sb.append(" <- ").append(edge).append(cn);
                    if (key.equals("?") && matchesHolder(cn, className, keywords)) key = cn;
                }
            }
            System.out.println(obj(id) + sb);
            hist.computeIfAbsent(key, x -> new int[1])[0]++;
        }
        System.out.println("-- FIRST-STRONG-HOLDER TALLY --");
        hist.entrySet().stream().sorted((a, b) -> b.getValue()[0] - a.getValue()[0])
            .forEach(e -> System.out.println("  " + e.getValue()[0] + "  " + e.getKey()));
    }

    private boolean matchesHolder(String cn, String target, String[] keywords) {
        if (keywords.length > 0) {
            for (String kw : keywords) if (!kw.isEmpty() && cn.contains(kw)) return true;
            return false;
        }
        if (cn.equals(target)) return false;
        int dollar = target.indexOf('$');
        String targetOuter = dollar > 0 ? target.substring(0, dollar) : target;
        if (cn.startsWith(targetOuter + "$")) return false;
        if (cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("jdk.")
            || cn.startsWith("sun.") || cn.startsWith("android.") || cn.startsWith("androidx.")
            || cn.startsWith("[") || cn.endsWith("[]")) return false;
        return true;
    }

    private void countObservers(String className) throws Exception {
        Set<Long> targets = new HashSet<>();
        for (int id : idsFor(className)) targets.add(snapshot.mapIdToAddress(id));
        System.out.println("\n==== OBSERVER OCCURRENCES OF " + className + " totalInstances=" + targets.size() + " ====");
        Map<Integer, Integer> perEvent = new HashMap<>();
        int grand = 0;
        for (int ncId : idsFor("org.telegram.messenger.NotificationCenter")) {
            IObject nc = snapshot.getObject(ncId);
            Object keysO = nc.resolveValue("observers.mKeys");
            Object valsO = nc.resolveValue("observers.mValues");
            Object sizeO = nc.resolveValue("observers.mSize");
            if (!(keysO instanceof org.eclipse.mat.snapshot.model.IPrimitiveArray keys)) continue;
            if (!(valsO instanceof org.eclipse.mat.snapshot.model.IObjectArray vals)) continue;
            int size = sizeO instanceof Integer ? (Integer) sizeO : keys.getLength();
            long[] valAddr = vals.getReferenceArray();
            for (int i = 0; i < size && i < valAddr.length; i++) {
                if (valAddr[i] == 0) continue;
                IObject list = snapshot.getObject(snapshot.mapAddressToId(valAddr[i]));
                Object edO = list.resolveValue("elementData");
                if (!(edO instanceof org.eclipse.mat.snapshot.model.IObjectArray ed)) continue;
                int cnt = 0;
                for (long a : ed.getReferenceArray()) if (a != 0 && targets.contains(a)) cnt++;
                if (cnt > 0) {
                    int key = ((Number) keys.getValueAt(i)).intValue();
                    perEvent.merge(key, cnt, Integer::sum);
                    grand += cnt;
                }
            }
        }
        System.out.println("  grandTotalObserverSlots=" + grand
            + "  (NotificationCenter event id -> occurrences)");
        perEvent.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
            .forEach(e -> System.out.println("    eventId=" + e.getKey() + " occurrences=" + e.getValue()));
    }

    // histogram of "which owner objects reference this instance", looking through Object[]/ArrayList containers
    private void ownerSignatures(String className) throws Exception {
        int[] ids = idsFor(className);
        Map<String, int[]> hist = new HashMap<>();
        for (int id : ids) {
            java.util.TreeSet<String> owners = new java.util.TreeSet<>();
            for (int from : snapshot.getInboundRefererIds(id)) {
                String cn = snapshot.getObject(from).getClazz().getName();
                if (cn.equals("java.lang.Object[]"))
                    for (int list : snapshot.getInboundRefererIds(from)) {
                        String lcn = snapshot.getObject(list).getClazz().getName();
                        for (int owner : snapshot.getInboundRefererIds(list))
                            owners.add(snapshot.getObject(owner).getClazz().getName() + refsTo(owner, list) + "/" + lcn);
                    }
                else owners.add(cn + refsTo(from, id));
            }
            hist.computeIfAbsent(owners.toString(), k -> new int[1])[0]++;
        }
        System.out.println("\n==== OWNERS " + className + " count=" + ids.length + " ====");
        hist.entrySet().stream().sorted((a, b) -> b.getValue()[0] - a.getValue()[0]).limit(20)
            .forEach(e -> System.out.println("  " + e.getValue()[0] + "  " + e.getKey()));
    }

    private void dumpQueues() throws Exception {
        for (int qid : idsFor("android.os.MessageQueue")) {
            IObject q = snapshot.getObject(qid);
            Map<String, int[]> hist = new HashMap<>();
            Map<String, long[]> whenRange = new HashMap<>();
            int n = 0;
            Object cur = q.resolveValue("mMessages");
            while (cur instanceof IObject && n < 2_000_000) {
                IObject m = (IObject) cur;
                Object cb = m.resolveValue("callback");
                Object target = m.resolveValue("target");
                String key = cb instanceof IObject ? ((IObject) cb).getClazz().getName()
                    : (target instanceof IObject ? ((IObject) target).getClazz().getName() : "null") + " what=" + m.resolveValue("what");
                hist.computeIfAbsent(key, k -> new int[1])[0]++;
                long when = ((Number) m.resolveValue("when")).longValue();
                long[] r = whenRange.computeIfAbsent(key, k -> new long[]{Long.MAX_VALUE, Long.MIN_VALUE});
                r[0] = Math.min(r[0], when); r[1] = Math.max(r[1], when);
                cur = m.resolveValue("next");
                n++;
            }
            if (n == 0) continue;
            System.out.println("\n==== QUEUE " + q.getTechnicalName() + " messages=" + n + " ====");
            hist.entrySet().stream().sorted((a, b) -> b.getValue()[0] - a.getValue()[0]).limit(25)
                .forEach(e -> System.out.println("  " + e.getValue()[0] + "  " + e.getKey()
                    + "  when=[" + whenRange.get(e.getKey())[0] + ".." + whenRange.get(e.getKey())[1] + "]"));
        }
    }

    private void dumpFields(String className, int limit, boolean withInbound) throws Exception {
        int[] ids = idsFor(className);
        System.out.println("\n==== FIELDS " + className + " count=" + ids.length + " ====");
        for (int i = 0; i < Math.min(ids.length, limit); i++) {
            printFields("OBJECT", ids[i]);
            if (withInbound)
                for (int from : snapshot.getInboundRefererIds(ids[i])) printFields("  <- " + refsTo(from, ids[i]), from);
        }
    }

    private String classSpecific(IObject o) {
        try {
            String s = o.getClassSpecificName();
            return s != null ? fixUtf(unescape(s)) : o.getTechnicalName();
        } catch (Exception e) {
            return o.getTechnicalName();
        }
    }

    private void dumpArray(long addr) throws Exception {
        IObject o = snapshot.getObject(snapshot.mapAddressToId(addr));
        System.out.println("ARRAY " + o.getTechnicalName());
        if (!(o instanceof org.eclipse.mat.snapshot.model.IObjectArray)) return;
        for (long ref : ((org.eclipse.mat.snapshot.model.IObjectArray) o).getReferenceArray()) {
            if (ref == 0) continue;
            IObject el = snapshot.getObject(snapshot.mapAddressToId(ref));
            String label = "java.lang.String".equals(el.getClazz().getName()) ? rawString(el) : classSpecific(el);
            System.out.println("  " + label + "  [" + el.getClazz().getName() + "]");
        }
    }

    /** read java.lang.String straight from its value array, bypassing MAT's corrupt-string resolver */
    private String rawString(IObject o) throws Exception {
        if (!(o instanceof org.eclipse.mat.snapshot.model.IInstance)) return "<not a string>";
        Object val = ((org.eclipse.mat.snapshot.model.IInstance) o).resolveValue("value");
        if (!(val instanceof org.eclipse.mat.snapshot.model.IPrimitiveArray)) return "<no value array>";
        org.eclipse.mat.snapshot.model.IPrimitiveArray arr = (org.eclipse.mat.snapshot.model.IPrimitiveArray) val;
        boolean chars = arr.getType() == 5;
        StringBuilder sb = new StringBuilder(arr.getLength());
        for (int i = 0; i < arr.getLength(); i++) {
            Number n = (Number) (arr.getValueAt(i) instanceof Character
                ? (int) ((Character) arr.getValueAt(i)).charValue()
                : arr.getValueAt(i));
            int b = n.intValue();
            if (chars) {
                sb.append((char) (b & 0xff)).append((char) (b >> 8));
            } else {
                sb.append((char) (b & 0xff));
            }
        }
        return sb.toString();
    }

    /** MAT escapes unprintable code units as literal backslash-u hex sequences */
    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 5 < s.length() && s.charAt(i + 1) == 'u') {
                try {
                    sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                    i += 5;
                    continue;
                } catch (NumberFormatException ignored) { }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** android hprof-conv dumps ascii packed as byte-swapped UTF-16 (lo,hi per code unit) */
    private static String fixUtf(String s) {
        StringBuilder sb = new StringBuilder(s.length() * 2);
        int printable = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char lo = (char) (c & 0xff);
            char hi = (char) (c >> 8);
            sb.append(lo).append(hi);
            if (lo >= 32 && lo < 127) printable++;
            if (hi >= 32 && hi < 127) printable++;
        }
        return s.length() > 0 && printable >= s.length() * 2 - 2 ? sb.toString() : s;
    }

    /** SharedPreferencesImpl (or any class with an mMap HashMap) -> per-key retained sizes */
    private void dumpMap(String className, int limit) throws Exception {
        int[] ids = sortedByRetained(idsFor(className));
        for (int i = 0; i < Math.min(ids.length, limit); i++) {
            IObject o = snapshot.getObject(ids[i]);
            if (!(o instanceof org.eclipse.mat.snapshot.model.IInstance)) continue;
            System.out.println("\n==== " + o.getTechnicalName() + " retained=" + retained(ids[i]) + " ====");
            Object file = ((org.eclipse.mat.snapshot.model.IInstance) o).resolveValue("mFile");
            if (file instanceof org.eclipse.mat.snapshot.model.IInstance) {
                Object path = ((org.eclipse.mat.snapshot.model.IInstance) file).resolveValue("path");
                if (path instanceof IObject) System.out.println("  file: " + classSpecific((IObject) path));
            }
            Object map = ((org.eclipse.mat.snapshot.model.IInstance) o).resolveValue("mMap");
            if (!(map instanceof org.eclipse.mat.snapshot.model.IInstance)) { System.out.println("  (no mMap)"); continue; }
            Object table = ((org.eclipse.mat.snapshot.model.IInstance) map).resolveValue("table");
            if (!(table instanceof org.eclipse.mat.snapshot.model.IObjectArray)) { System.out.println("  (empty map)"); continue; }
            java.util.List<String> rows = new ArrayList<>();
            for (long refAddr : ((org.eclipse.mat.snapshot.model.IObjectArray) table).getReferenceArray()) {
                if (refAddr == 0) continue;
                int nodeId = snapshot.mapAddressToId(refAddr);
                while (nodeId >= 0) {
                    IObject node = snapshot.getObject(nodeId);
                    if (!(node instanceof org.eclipse.mat.snapshot.model.IInstance)) break;
                    org.eclipse.mat.snapshot.model.IInstance ni = (org.eclipse.mat.snapshot.model.IInstance) node;
                    Object k = ni.resolveValue("key");
                    Object v = ni.resolveValue("value");
                    String key = k instanceof IObject ? classSpecific((IObject) k) : String.valueOf(k);
                    long size = v instanceof IObject ? retained(((IObject) v).getObjectId()) : 0;
                    String vc = v instanceof IObject ? ((IObject) v).getClazz().getName() : "null";
                    String text = v instanceof IObject ? classSpecific((IObject) v) : "";
                    if (text.length() > 160) text = text.substring(0, 160) + "...";
                    rows.add(size + "\t" + vc + "\t" + key + "\t" + text.replace('\n', ' '));
                    Object next = ni.resolveValue("next");
                    nodeId = next instanceof IObject ? ((IObject) next).getObjectId() : -1;
                }
            }
            rows.sort((a, b) -> Long.compare(Long.parseLong(b.split("\t")[0]), Long.parseLong(a.split("\t")[0])));
            for (int r = 0; r < Math.min(rows.size(), 30); r++) System.out.println("  " + rows.get(r));
            Map<String, Integer> pref = new HashMap<>();
            for (String row : rows) {
                String k = row.split("\t")[2];
                pref.merge(k.length() >= 3 ? k.substring(0, 3) : k, 1, Integer::sum);
            }
            System.out.println("  key-prefixes: " + pref);
            System.out.println("  entries=" + rows.size());
        }
    }

    private void printFields(String label, int id) throws Exception {
        IObject o = snapshot.getObject(id);
        System.out.println(label + " " + o.getTechnicalName());
        if (!(o instanceof org.eclipse.mat.snapshot.model.IInstance)) return;
        for (org.eclipse.mat.snapshot.model.Field f : ((org.eclipse.mat.snapshot.model.IInstance) o).getFields()) {
            Object v = f.getValue();
            if (v instanceof org.eclipse.mat.snapshot.model.ObjectReference) {
                long addr = ((org.eclipse.mat.snapshot.model.ObjectReference) v).getObjectAddress();
                v = addr == 0 ? "null" : obj(snapshot.mapAddressToId(addr));
            }
            System.out.println("      " + f.getName() + " = " + v);
        }
    }

    // ---- helpers ------------------------------------------------------------

    private Map<IClass, Set<String>> excludeMap() throws Exception {
        Map<IClass, Set<String>> m = new HashMap<>();
        String[] refs = {"java.lang.ref.WeakReference", "java.lang.ref.SoftReference",
            "java.lang.ref.PhantomReference", "java.lang.ref.FinalizerReference"};
        for (String r : refs)
            for (IClass c : snapshot.getClassesByName(r, true)) {
                Set<String> s = new HashSet<>();
                s.add("referent");
                m.put(c, s);
            }
        // HEAPPROBE_EXCLUDE="android.os.Message:callback,Foo$Bar:field" also cuts these edges
        String extra = System.getenv("HEAPPROBE_EXCLUDE");
        if (extra != null && !extra.isEmpty())
            for (String spec : extra.split(",")) {
                String[] parts = spec.split(":", 2);
                for (IClass c : snapshot.getClassesByName(parts[0], true))
                    m.computeIfAbsent(c, k -> new HashSet<>()).add(parts[1]);
            }
        return m;
    }

    private int[] idsFor(String className) throws Exception {
        Collection<IClass> classes = snapshot.getClassesByName(className, false);
        if (classes == null || classes.isEmpty()) {
            System.out.println("  (no class named " + className + ")");
            return new int[0];
        }
        List<Integer> ids = new ArrayList<>();
        for (IClass c : classes) for (int id : c.getObjectIds()) ids.add(id);
        int[] r = new int[ids.size()];
        for (int i = 0; i < r.length; i++) r[i] = ids.get(i);
        return r;
    }

    private long retained(int id) {
        try { return snapshot.getRetainedHeapSize(id); } catch (Exception e) { return 0; }
    }

    private String refsTo(int from, int to) throws Exception {
        long target = snapshot.mapIdToAddress(to);
        List<String> names = new ArrayList<>();
        for (NamedReference ref : snapshot.getObject(from).getOutboundReferences())
            if (ref.getObjectAddress() == target) names.add(ref.getName());
        return names.toString();
    }

    private String obj(int id) {
        if (id < 0) return "<none>";
        try { return snapshot.getObject(id).getTechnicalName(); } catch (Exception e) { return "#" + id; }
    }

    private int[] sortedByRetained(int[] ids) {
        Integer[] boxed = Arrays.stream(ids).boxed().toArray(Integer[]::new);
        Arrays.sort(boxed, Comparator.comparingLong((Integer id) -> retained(id)).reversed());
        int[] sorted = new int[boxed.length];
        for (int i = 0; i < boxed.length; i++) sorted[i] = boxed[i];
        return sorted;
    }
}
