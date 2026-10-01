package jarrunner.jextract_teavm;

import module java.base;
import org.openjdk.jextract.*;
import org.openjdk.jextract.Declaration.*;

/** Every top-level name jextract saw, plus enum constants hoisted out of their enums. */
public final class SymbolTable {
    /** C keeps struct/union tags in their own namespace ({@code struct stat} vs the function {@code stat}). */
    public static final String STRUCT_PREFIX = "struct:";
    final Map<String, Declaration> byName = new LinkedHashMap<>();
    final Map<String, Scoped> tags = new LinkedHashMap<>();

    public SymbolTable(Scoped toplevel) {
        toplevel.members().forEach(this::index);
    }

    void index(Declaration d) {
        if (d instanceof Scoped s && (s.kind() == Scoped.Kind.STRUCT || s.kind() == Scoped.Kind.UNION)) tags.putIfAbsent(s.name(), s);
        else byName.putIfAbsent(d.name(), d);
        switch (d) {
            case Scoped s when s.kind() == Scoped.Kind.ENUM -> s.members().forEach(this::index);
            case Typedef t -> Attrs.nested(t).forEach(this::index);
            default -> {}
        }
    }

    /** An ordinary name, else a struct/union tag of that name; {@code struct:NAME} asks for the tag only. */
    public Optional<Declaration> find(String name) {
        if (name.startsWith(STRUCT_PREFIX)) return Optional.ofNullable(tags.get(name.substring(STRUCT_PREFIX.length())));
        return Optional.ofNullable(byName.get(name)).or(() -> Optional.ofNullable(tags.get(name)));
    }

    public static Optional<Scoped> structOf(Declaration d) {
        return switch (d) {
            case Scoped s when s.kind() == Scoped.Kind.STRUCT || s.kind() == Scoped.Kind.UNION -> Optional.of(s);
            case Typedef t -> structOf(t.type());
            default -> Optional.empty();
        };
    }

    static Optional<Scoped> structOf(Type t) {
        return switch (TypeMap.unqualified(t)) {
            case Type.Declared d when d.tree().kind() != Scoped.Kind.ENUM -> Optional.of(d.tree());
            default -> Optional.empty();
        };
    }
}
