import module java.base;

/** Hand-maintained vs generated bindings, symbol by symbol: @Import signatures, constant values, struct layouts.
 *  java CompareBindings.java <handDir> <genDir>   (each containing WinApi.java and WinOffsets.java) */
void main(String[] a) throws IOException {
    var hand = Path.of(a[0]);
    var gen = Path.of(a[1]);
    var bad = 0;
    bad += compare("@Import signatures", imports(hand.resolve("WinApi.java")), imports(gen.resolve("WinApi.java")));
    bad += compare("constants", constants(hand.resolve("WinApi.java")), constants(gen.resolve("WinApi.java")));
    bad += compare("struct SIZE/offsets", layouts(hand.resolve("WinOffsets.java")), layouts(gen.resolve("WinOffsets.java")));
    System.out.println(bad == 0 ? "RESULT: every hand-maintained binding is reproduced exactly" : "RESULT: " + bad + " difference(s)");
}

int compare(String what, Map<String, String> hand, Map<String, String> gen) {
    var same = 0;
    var diffs = new ArrayList<String>();
    for (var e : hand.entrySet()) {
        var g = gen.get(e.getKey());
        if (g == null) diffs.add("  MISSING from generated: " + e.getKey());
        else if (!g.equals(e.getValue())) diffs.add("  DIFFERS " + e.getKey() + "\n      hand: " + e.getValue() + "\n      gen:  " + g);
        else same++;
    }
    var extra = gen.keySet().stream().filter(k -> !hand.containsKey(k)).toList();
    System.out.printf("%s: %d identical, %d differ/missing, %d only in generated%n", what, same, diffs.size(), extra.size());
    diffs.forEach(System.out::println);
    if (!extra.isEmpty()) System.out.println("  only in generated: " + String.join(", ", extra));
    return diffs.size();
}

Map<String, String> imports(Path f) throws IOException {
    var m = new TreeMap<String, String>();
    var p = Pattern.compile("@Import\\(name = \"(\\w+)\"\\)\\s+(?:public\\s+)?static native (\\S+) (\\w+)\\(([^)]*)\\);");
    var x = p.matcher(Files.readString(f));
    while (x.find()) {
        var params = Arrays.stream(x.group(4).split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.split("\\s+")[0]).toList();
        m.put(x.group(1), x.group(2) + " " + x.group(3) + "(" + String.join(", ", params) + ")");
    }
    return m;
}

Map<String, String> constants(Path f) throws IOException {
    var m = new TreeMap<String, String>();
    var x = Pattern.compile("static final (\\w+) ([A-Z_0-9a-z]+) = ([^;]+);").matcher(Files.readString(f));
    while (x.find()) m.put(x.group(2), x.group(1) + " " + value(x.group(1), x.group(3).trim()));
    return m;
}

String value(String type, String v) {
    var addr = Pattern.compile("Address\\.from(?:Int|Long)\\((-?\\d+)L?\\)").matcher(v);
    if (addr.matches()) return "Address(" + Long.parseLong(addr.group(1)) + ")";
    if (v.startsWith("\"")) return v;
    var n = v.replace("(int)", "").replace("(short)", "").replace("(byte)", "").trim().replaceAll("[lL]$", "");
    var neg = n.startsWith("-");
    var mag = neg ? n.substring(1) : n;
    var bits = mag.startsWith("0x") || mag.startsWith("0X") ? Long.parseUnsignedLong(mag.substring(2), 16) : Long.parseLong(mag);
    if (neg) bits = -bits;
    return type.equals("int") ? Integer.toString((int) bits) : Long.toString(bits);
}

Map<String, String> layouts(Path f) throws IOException {
    var m = new TreeMap<String, String>();
    var cls = "";
    var c = Pattern.compile("class (\\w+) \\{");
    var o = Pattern.compile("public static final int (\\w+) = (\\d+);");
    for (var line : Files.readAllLines(f)) {
        var cm = c.matcher(line);
        if (cm.find() && !line.contains("WinOffsets")) cls = cm.group(1);
        var om = o.matcher(line);
        if (om.find() && !cls.isEmpty()) m.put(cls + "." + om.group(1), om.group(2));
    }
    return m;
}
