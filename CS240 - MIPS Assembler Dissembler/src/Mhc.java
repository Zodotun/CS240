import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A deliberately small MIPS assembler/disassembler for fizzbuzz2.asm. Java 17+. */
public final class Mhc {
    public static final long TEXT_BASE = 0x00400000L;
    public static final long DATA_BASE = 0x10010000L;
    private static final Pattern LABEL = Pattern.compile("^([A-Za-z_.$][A-Za-z0-9_.$]*):");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_.$][A-Za-z0-9_.$]*");
    private static final String[] REGISTERS = {
        "zero", "at", "v0", "v1", "a0", "a1", "a2", "a3",
        "t0", "t1", "t2", "t3", "t4", "t5", "t6", "t7",
        "s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7",
        "t8", "t9", "k0", "k1", "gp", "sp", "fp", "ra"
    };
    private static final Set<String> MNEMONICS = Set.of(
        "li", "la", "move", "rem", "beqz", "addi", "bne", "j", "syscall",
        // Only the real instructions needed by the pseudoinstructions above.
        "addiu", "lui", "ori", "addu", "div", "mfhi", "beq"
    );

    record Symbol(boolean text, int offset) {
        long address() { return (text ? TEXT_BASE : DATA_BASE) + offset; }
    }
    record DataString(int offset, int length) {} // Includes the terminating NUL.
    record Instruction(int line, int offset, String mnemonic, List<String> operands) {}
    public record Image(int[] words, byte[] data, Map<String, Symbol> symbols,
                        List<DataString> strings) {}
    private record Decoded(String mnemonic, String operands, Long target) {}
    private record Metadata(int textWords, int dataBytes, Map<String, Symbol> symbols,
                            List<DataString> strings) {}

    private Mhc() {}

    public static void main(String[] args) {
        try {
            if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
                usage();
                return;
            }
            switch (args[0]) {
                case "assemble" -> {
                    require(args.length == 2 || args.length == 3,
                            "assemble INPUT.asm [OUTPUT_PREFIX]");
                    Path input = Path.of(args[1]);
                    Path prefix = args.length == 3 ? Path.of(args[2]) :
                            Path.of(removeSuffix(input.toString(), ".asm"));
                    Image image = assemble(Files.readString(input, StandardCharsets.UTF_8));
                    writeImage(image, prefix, input);
                    System.out.printf("Assembled %d instructions (%d binary bytes), %d data bytes.%n",
                            image.words.length, image.words.length * 4, image.data.length);
                    System.out.println("Output prefix: " + prefix);
                }
                case "disassemble" -> {
                    require(args.length == 3 || args.length == 4 || args.length == 5,
                            "disassemble INPUT.mhc-{ascii|bin} OUTPUT.asm [--text-only | --map MAP]");
                    Path input = Path.of(args[1]);
                    Path output = Path.of(args[2]);
                    String prefix = machinePrefix(input);
                    Path map = Path.of(prefix + ".mhc-map");
                    boolean textOnly = false;
                    if (args.length == 4) {
                        require(args[3].equals("--text-only"), "Expected --text-only");
                        textOnly = true;
                    } else if (args.length == 5) {
                        require(args[3].equals("--map"), "Expected --map MAP");
                        map = Path.of(args[4]);
                        require(Files.isRegularFile(map), "Map file does not exist: " + map);
                    }
                    Metadata metadata = !textOnly && Files.isRegularFile(map) ? readMap(map) : null;
                    int[] words = readWords(input);
                    Path dataPath = Path.of(prefix + ".data" + machineExtension(input));
                    byte[] data = metadata == null ? new byte[0] : readBytes(dataPath);
                    String source = disassemble(words, data, metadata);
                    List<Path> protectedInputs = metadata == null ? List.of(input) :
                            List.of(input, map, dataPath);
                    for (Path protectedInput : protectedInputs) {
                        require(!sameFile(output, protectedInput), "Output would overwrite an input file");
                    }
                    writeText(output, source);
                    System.out.printf("Disassembled %d instructions to %s%s%n", words.length, output,
                            metadata == null ? " (text only; data and original labels unavailable)" : "");
                }
                default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
            }
        } catch (IllegalArgumentException | IOException ex) {
            System.err.println("mhc: " + ex.getMessage());
            System.exit(1);
        }
    }

    private static void usage() {
        System.out.println("""
                MHC: a small MIPS assembler and disassembler (Java 17+)
                  java -jar mhc.jar assemble INPUT.asm [OUTPUT_PREFIX]
                  java -jar mhc.jar disassemble INPUT.mhc-bin OUTPUT.asm
                  java -jar mhc.jar disassemble INPUT.mhc-ascii OUTPUT.asm
                    Optional: --text-only, or --map PATH.mhc-map

                Assembly emits instruction .mhc-ascii/.mhc-bin files, separate
                .data.mhc-ascii/.data.mhc-bin files, and a .mhc-map metadata file.
                Binary files are header-free, big-endian. ASCII contains only
                0/1 and LF. Disassembly automatically uses an adjacent map.
                """);
    }

    /** Two passes: calculate expanded sizes/labels, then resolve operands and encode. */
    public static Image assemble(String source) {
        List<Instruction> instructions = new ArrayList<>();
        Map<String, Symbol> symbols = new LinkedHashMap<>();
        List<DataString> strings = new ArrayList<>();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int textBytes = 0;
        boolean text = true;
        String[] lines = source.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            try {
                String line = stripComment(lines[i]).trim();
                if (i == 0 && line.startsWith("\uFEFF")) line = line.substring(1).trim();
                if (line.isEmpty()) continue;
                Matcher matcher;
                while ((matcher = LABEL.matcher(line)).find()) {
                    String name = matcher.group(1);
                    require(!symbols.containsKey(name), "Duplicate label: " + name);
                    symbols.put(name, new Symbol(text, text ? textBytes : data.size()));
                    line = line.substring(matcher.end()).trim();
                }
                if (line.isEmpty()) continue;
                if (line.equals(".text") || line.equals(".data")) {
                    text = line.equals(".text");
                    continue;
                }
                int split = 0;
                while (split < line.length() && !Character.isWhitespace(line.charAt(split))) split++;
                String op = line.substring(0, split).toLowerCase(Locale.ROOT);
                String tail = line.substring(split).trim();
                if (op.equals(".asciiz")) {
                    require(!text, ".asciiz must appear in .data");
                    byte[] bytes = quotedBytes(tail, '"');
                    strings.add(new DataString(data.size(), bytes.length + 1));
                    data.writeBytes(bytes);
                    data.write(0);
                    continue;
                }
                require(!op.startsWith("."), "Unsupported directive: " + op);
                require(text, "Instructions must appear in .text");
                require(MNEMONICS.contains(op), "Unsupported instruction: " + op);
                Instruction instruction = new Instruction(i + 1, textBytes, op, operands(tail));
                instructions.add(instruction);
                textBytes = Math.addExact(textBytes, wordCount(instruction) * 4);
            } catch (IllegalArgumentException | ArithmeticException ex) {
                throw new IllegalArgumentException("Line " + (i + 1) + ": " + ex.getMessage(), ex);
            }
        }
        require(TEXT_BASE + textBytes <= DATA_BASE, "Text overlaps the data segment");
        int[] words = new int[textBytes / 4];
        for (Instruction instruction : instructions) {
            try {
                int[] encoded = encode(instruction, symbols);
                require(encoded.length == wordCount(instruction), "Internal instruction-size mismatch");
                System.arraycopy(encoded, 0, words, instruction.offset / 4, encoded.length);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Line " + instruction.line + ": " + ex.getMessage(), ex);
            }
        }
        return new Image(words, data.toByteArray(), symbols, strings);
    }

    private static int wordCount(Instruction instruction) {
        return switch (instruction.mnemonic) {
            case "rem", "la" -> 2;
            case "li" -> {
                count(instruction, 2);
                int value = literal(instruction.operands.get(1));
                yield value >= -32768 && value <= 65535 ? 1 : 2;
            }
            default -> 1;
        };
    }

    private static int[] encode(Instruction in, Map<String, Symbol> symbols) {
        List<String> a = in.operands;
        long pc = TEXT_BASE + in.offset;
        switch (in.mnemonic) {
            case "li": {
                count(in, 2);
                int rt = register(a.get(0)), value = literal(a.get(1));
                if (value >= -32768 && value <= 32767) return one(iType(9, 0, rt, value));
                if (value >= 0 && value <= 65535) return one(iType(13, 0, rt, value));
                return new int[] {iType(15, 0, rt, value >>> 16), iType(13, rt, rt, value)};
            }
            case "la": {
                count(in, 2);
                int rt = register(a.get(0));
                int address = (int) address(a.get(1), symbols, false);
                return new int[] {iType(15, 0, rt, address >>> 16), iType(13, rt, rt, address)};
            }
            case "move": {
                count(in, 2);
                return one(rType(register(a.get(1)), 0, register(a.get(0)), 0x21));
            }
            case "rem": {
                count(in, 3);
                return new int[] {rType(register(a.get(1)), register(a.get(2)), 0, 0x1A),
                        rType(0, 0, register(a.get(0)), 0x10)};
            }
            case "beqz": {
                count(in, 2);
                return one(iType(4, register(a.get(0)), 0, branch(a.get(1), pc, symbols)));
            }
            case "beq", "bne": {
                count(in, 3);
                return one(iType(in.mnemonic.equals("beq") ? 4 : 5,
                        register(a.get(0)), register(a.get(1)), branch(a.get(2), pc, symbols)));
            }
            case "addi", "addiu": {
                count(in, 3);
                int immediate = signed16(literal(a.get(2)));
                return one(iType(in.mnemonic.equals("addi") ? 8 : 9,
                        register(a.get(1)), register(a.get(0)), immediate));
            }
            case "ori": {
                count(in, 3);
                int immediate = unsigned16(literal(a.get(2)));
                return one(iType(13, register(a.get(1)), register(a.get(0)), immediate));
            }
            case "lui": {
                count(in, 2);
                return one(iType(15, 0, register(a.get(0)), unsigned16(literal(a.get(1)))));
            }
            case "addu": {
                count(in, 3);
                return one(rType(register(a.get(1)), register(a.get(2)), register(a.get(0)), 0x21));
            }
            case "div": {
                count(in, 2);
                return one(rType(register(a.get(0)), register(a.get(1)), 0, 0x1A));
            }
            case "mfhi": {
                count(in, 1);
                return one(rType(0, 0, register(a.get(0)), 0x10));
            }
            case "j": {
                count(in, 1);
                long target = address(a.get(0), symbols, true);
                require((target & 3) == 0, "Jump target must be word-aligned");
                require((target & 0xF0000000L) == ((pc + 4) & 0xF0000000L),
                        "Jump target is outside the PC+4 256 MiB region");
                return one((2 << 26) | (int) ((target >>> 2) & 0x03FFFFFF));
            }
            case "syscall":
                count(in, 0);
                return one(0x0000000C);
            default: throw new IllegalArgumentException("Unsupported instruction: " + in.mnemonic);
        }
    }

    private static int branch(String operand, long pc, Map<String, Symbol> symbols) {
        long target = address(operand, symbols, true);
        long delta = target - (pc + 4);
        require((delta & 3) == 0, "Branch target must be word-aligned");
        long offset = delta / 4;
        require(offset >= -32768 && offset <= 32767, "Branch target is out of signed 16-bit range");
        return (int) offset;
    }

    // Numeric branch and jump operands are absolute addresses, not encoded offsets.
    private static long address(String value, Map<String, Symbol> symbols, boolean textOnly) {
        Symbol symbol = symbols.get(value);
        if (symbol != null) {
            require(!textOnly || symbol.text, "Control-flow target is a data label: " + value);
            return symbol.address();
        }
        require(!NAME.matcher(value).matches(), "Undefined label: " + value);
        return Integer.toUnsignedLong(literal(value));
    }

    private static int[] one(int value) { return new int[] {value}; }
    private static int rType(int rs, int rt, int rd, int funct) {
        return (rs << 21) | (rt << 16) | (rd << 11) | funct;
    }
    private static int iType(int opcode, int rs, int rt, int immediate) {
        return (opcode << 26) | (rs << 21) | (rt << 16) | (immediate & 0xFFFF);
    }
    private static int signed16(int value) {
        require(value >= -32768 && value <= 32767, "Expected signed 16-bit immediate");
        return value;
    }
    private static int unsigned16(int value) {
        require(value >= 0 && value <= 65535, "Expected unsigned 16-bit immediate");
        return value;
    }
    private static void count(Instruction in, int count) {
        require(in.operands.size() == count, in.mnemonic + " expects " + count + " operands");
    }

    static int register(String token) {
        require(token.startsWith("$"), "Expected register, got: " + token);
        String name = token.substring(1).toLowerCase(Locale.ROOT);
        if (name.equals("s8")) return 30;
        for (int i = 0; i < REGISTERS.length; i++) if (REGISTERS[i].equals(name)) return i;
        if (name.matches("[0-9]{1,2}")) {
            int value = Integer.parseInt(name);
            if (value <= 31) return value;
        }
        throw new IllegalArgumentException("Unknown register: " + token);
    }
    private static String reg(int value) { return "$" + REGISTERS[value]; }

    static int literal(String token) {
        token = token.trim();
        if (token.startsWith("'")) {
            byte[] bytes = quotedBytes(token, '\'');
            require(bytes.length == 1, "Character literal must contain exactly one byte");
            return bytes[0] & 255;
        }
        String number = token;
        boolean negative = number.startsWith("-");
        if (negative || number.startsWith("+")) number = number.substring(1);
        int radix = 10;
        if (number.startsWith("0x") || number.startsWith("0X")) {
            radix = 16;
            number = number.substring(2);
        } else if (number.startsWith("0b") || number.startsWith("0B")) {
            radix = 2;
            number = number.substring(2);
        }
        String digits = radix == 16 ? "[0-9a-fA-F]+" : radix == 2 ? "[01]+" : "[0-9]+";
        require(number.matches(digits), "Invalid integer literal: " + token);
        try {
            long value = Long.parseLong(number, radix);
            if (negative) value = -value;
            require(value >= Integer.MIN_VALUE && value <= 0xFFFFFFFFL,
                    "Integer literal is outside 32-bit range: " + token);
            return (int) value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Integer literal is outside 32-bit range: " + token);
        }
    }

    private static List<String> operands(String text) {
        if (text.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        char quote = 0;
        boolean escape = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == ',') {
                result.add(text.substring(start, i).trim());
                start = i + 1;
            }
        }
        require(quote == 0, "Unterminated quoted operand");
        result.add(text.substring(start).trim());
        for (String operand : result) require(!operand.isEmpty(), "Empty operand");
        return result;
    }

    private static String stripComment(String text) {
        char quote = 0;
        boolean escape = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '#') return text.substring(0, i);
        }
        return text;
    }

    private static byte[] quotedBytes(String text, char quote) {
        require(text.length() >= 2 && text.charAt(0) == quote, "Expected quoted literal");
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == quote) {
                require(text.substring(i + 1).trim().isEmpty(), "Unexpected text after quoted literal");
                return result.toByteArray();
            }
            if (c == '\\') {
                require(++i < text.length(), "Unterminated escape");
                c = text.charAt(i);
                switch (c) {
                    case 'n' -> c = '\n';
                    case 'r' -> c = '\r';
                    case 't' -> c = '\t';
                    case 'b' -> c = '\b';
                    case 'f' -> c = '\f';
                    case '0' -> c = 0;
                    case '\\', '\'', '"' -> { }
                    case 'x' -> {
                        require(i + 2 < text.length(), "Expected two hex digits after \\x");
                        String hex = text.substring(i + 1, i + 3);
                        require(hex.matches("[0-9a-fA-F]{2}"), "Invalid hex byte escape");
                        c = (char) Integer.parseInt(hex, 16);
                        i += 2;
                    }
                    default -> throw new IllegalArgumentException("Unsupported escape: \\" + c);
                }
            }
            require(c <= 255, "String characters must fit a byte; use \\xNN escapes");
            result.write(c);
        }
        throw new IllegalArgumentException("Unterminated quoted literal");
    }

    private static String quote(byte[] data, int offset, int length) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = offset; i < offset + length; i++) {
            int value = data[i] & 255;
            result.append(switch (value) {
                case 0 -> "\\0";
                case 9 -> "\\t";
                case 10 -> "\\n";
                case 13 -> "\\r";
                case 34 -> "\\\"";
                case 92 -> "\\\\";
                default -> value >= 32 && value <= 126 ? Character.toString((char) value) :
                        String.format(Locale.ROOT, "\\x%02X", value);
            });
        }
        return result.append('"').toString();
    }

    /** Canonical real-instruction output; pseudo source spelling cannot be recovered from bits. */
    static String disassemble(int[] words, byte[] data, Metadata metadata) {
        Map<String, Symbol> symbols = metadata == null ? new LinkedHashMap<>() :
                new LinkedHashMap<>(metadata.symbols);
        if (metadata != null) {
            require(metadata.textWords == words.length, "Map instruction count does not match input");
            require(metadata.dataBytes == data.length, "Map data size does not match data file");
            validateMetadata(metadata, data);
        }
        List<Decoded> decoded = new ArrayList<>();
        Set<String> names = new HashSet<>(symbols.keySet());
        Map<Long, String> textTargets = new LinkedHashMap<>();
        for (Map.Entry<String, Symbol> entry : symbols.entrySet()) {
            if (entry.getValue().text) textTargets.putIfAbsent(entry.getValue().address(), entry.getKey());
        }
        for (int index = 0; index < words.length; index++) {
            long pc = TEXT_BASE + 4L * index;
            Decoded d = decode(words[index], pc);
            decoded.add(d);
            if (d.target != null && d.target >= TEXT_BASE && d.target <= TEXT_BASE + words.length * 4L
                    && !textTargets.containsKey(d.target)) {
                String label = String.format(Locale.ROOT, "L_%08X", d.target);
                while (!names.add(label)) label += "_";
                symbols.put(label, new Symbol(true, (int) (d.target - TEXT_BASE)));
                textTargets.put(d.target, label);
            }
        }
        StringBuilder source = new StringBuilder("# Canonical MHC disassembly; pseudoinstructions are expanded.\n");
        if (metadata != null && (data.length > 0 || symbols.values().stream().anyMatch(s -> !s.text))) {
            source.append(".data\n");
            Map<Integer, List<String>> labels = labels(symbols, false);
            for (DataString string : metadata.strings) {
                appendLabels(source, labels, string.offset);
                source.append("    .asciiz ").append(quote(data, string.offset, string.length - 1)).append('\n');
            }
            appendLabels(source, labels, data.length);
            source.append('\n');
        }
        source.append(".text\n");
        Map<Integer, List<String>> labels = labels(symbols, true);
        for (int index = 0; index < words.length; index++) {
            appendLabels(source, labels, index * 4);
            Decoded d = decoded.get(index);
            source.append("    ").append(d.mnemonic);
            if (!d.operands.isEmpty() || d.target != null) source.append(' ').append(d.operands);
            if (d.target != null) source.append(textTargets.getOrDefault(d.target, hex(d.target)));
            source.append(String.format(Locale.ROOT, "  # %s: %08X%n", hex(TEXT_BASE + index * 4L), words[index]));
        }
        appendLabels(source, labels, words.length * 4);
        return source.toString();
    }

    private static Decoded decode(int word, long pc) {
        int op = word >>> 26, rs = (word >>> 21) & 31, rt = (word >>> 16) & 31;
        int rd = (word >>> 11) & 31, shift = (word >>> 6) & 31, funct = word & 63;
        int imm = word & 65535;
        if (op == 0) {
            if (word == 12) return new Decoded("syscall", "", null);
            if (funct == 0x21 && shift == 0)
                return new Decoded("addu", reg(rd) + ", " + reg(rs) + ", " + reg(rt), null);
            if (funct == 0x1A && rd == 0 && shift == 0)
                return new Decoded("div", reg(rs) + ", " + reg(rt), null);
            if (funct == 0x10 && rs == 0 && rt == 0 && shift == 0)
                return new Decoded("mfhi", reg(rd), null);
        } else if (op == 8 || op == 9) {
            return new Decoded(op == 8 ? "addi" : "addiu",
                    reg(rt) + ", " + reg(rs) + ", " + (short) imm, null);
        } else if (op == 13) {
            return new Decoded("ori", reg(rt) + ", " + reg(rs) + ", " + imm, null);
        } else if (op == 15 && rs == 0) {
            return new Decoded("lui", reg(rt) + ", " + imm, null);
        } else if (op == 4 || op == 5) {
            long target = (pc + 4 + ((long) (short) imm * 4)) & 0xFFFFFFFFL;
            return new Decoded(op == 4 ? "beq" : "bne", reg(rs) + ", " + reg(rt) + ", ", target);
        } else if (op == 2) {
            long target = ((pc + 4) & 0xF0000000L) | ((word & 0x03FFFFFFL) << 2);
            return new Decoded("j", "", target);
        }
        throw new IllegalArgumentException(String.format(Locale.ROOT,
                "Unsupported or malformed instruction %08X at %s", word, hex(pc)));
    }

    private static Map<Integer, List<String>> labels(Map<String, Symbol> symbols, boolean text) {
        Map<Integer, List<String>> result = new TreeMap<>();
        symbols.forEach((name, symbol) -> {
            if (symbol.text == text) result.computeIfAbsent(symbol.offset, k -> new ArrayList<>()).add(name);
        });
        return result;
    }
    private static void appendLabels(StringBuilder source, Map<Integer, List<String>> labels, int offset) {
        for (String label : labels.getOrDefault(offset, List.of())) source.append(label).append(":\n");
    }

    public static void writeImage(Image image, Path prefix) throws IOException {
        writeImage(image, prefix, null);
    }
    private static void writeImage(Image image, Path prefix, Path input) throws IOException {
        String p = prefix.toString();
        List<Path> outputs = List.of(Path.of(p + ".mhc-ascii"), Path.of(p + ".mhc-bin"),
                Path.of(p + ".data.mhc-ascii"), Path.of(p + ".data.mhc-bin"), Path.of(p + ".mhc-map"));
        if (input != null) for (Path output : outputs)
            require(!sameFile(output, input), "Output would overwrite the source file");
        byte[] text = wordsToBytes(image.words);
        writeText(outputs.get(0), ascii(text));
        writeBytes(outputs.get(1), text);
        writeText(outputs.get(2), ascii(image.data));
        writeBytes(outputs.get(3), image.data);
        writeText(outputs.get(4), mapText(image));
    }

    public static byte[] wordsToBytes(int[] words) {
        ByteBuffer buffer = ByteBuffer.allocate(Math.multiplyExact(words.length, 4)).order(ByteOrder.BIG_ENDIAN);
        for (int word : words) buffer.putInt(word);
        return buffer.array();
    }
    public static int[] bytesToWords(byte[] bytes) {
        require(bytes.length % 4 == 0, "Instruction binary length must be a multiple of 4 bytes");
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int[] words = new int[bytes.length / 4];
        for (int i = 0; i < words.length; i++) words[i] = buffer.getInt();
        return words;
    }
    public static String ascii(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            for (int bit = 7; bit >= 0; bit--) text.append(((bytes[i] & 255) >>> bit) & 1);
            if (i % 4 == 3 || i == bytes.length - 1) text.append('\n');
        }
        return text.toString();
    }
    public static byte[] parseAscii(String text) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int value = 0, bits = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) continue;
            require(c == '0' || c == '1', "ASCII machine code must contain only 0, 1, and whitespace");
            value = (value << 1) | (c - '0');
            if (++bits == 8) { output.write(value); value = 0; bits = 0; }
        }
        require(bits == 0, "ASCII bit count must be a multiple of 8");
        return output.toByteArray();
    }
    public static int[] readWords(Path input) throws IOException { return bytesToWords(readBytes(input)); }
    private static byte[] readBytes(Path input) throws IOException {
        return machineExtension(input).equals(".mhc-bin") ? Files.readAllBytes(input) :
                parseAscii(Files.readString(input, StandardCharsets.US_ASCII));
    }
    private static String machineExtension(Path input) {
        String name = input.toString();
        if (name.endsWith(".mhc-bin")) return ".mhc-bin";
        if (name.endsWith(".mhc-ascii")) return ".mhc-ascii";
        throw new IllegalArgumentException("Expected a .mhc-bin or .mhc-ascii input");
    }
    private static String machinePrefix(Path input) {
        return removeSuffix(input.toString(), machineExtension(input));
    }
    private static String removeSuffix(String text, String suffix) {
        return text.endsWith(suffix) ? text.substring(0, text.length() - suffix.length()) : text;
    }

    private static String mapText(Image image) {
        StringBuilder map = new StringBuilder("# MHC section/symbol metadata; no source text or instructions.\n");
        map.append("format=mhc-map-1\nbyteOrder=big-endian\ntextBase=0x00400000\ndataBase=0x10010000\n");
        map.append("textWords=").append(image.words.length).append('\n');
        map.append("dataBytes=").append(image.data.length).append('\n');
        map.append("stringCount=").append(image.strings.size()).append('\n');
        for (int i = 0; i < image.strings.size(); i++) {
            DataString string = image.strings.get(i);
            map.append("string.").append(i).append('=').append(string.offset).append(':').append(string.length).append('\n');
        }
        image.symbols.forEach((name, symbol) -> map.append("symbol.").append(name).append('=')
                .append(symbol.text ? "text:" : "data:").append(symbol.offset).append('\n'));
        return map.toString();
    }

    private static Metadata readMap(Path input) throws IOException {
        Properties map = new Properties();
        map.load(new StringReader(Files.readString(input, StandardCharsets.UTF_8)));
        require("mhc-map-1".equals(map.getProperty("format")), "Unsupported map format");
        require("big-endian".equals(map.getProperty("byteOrder")), "Unsupported map byte order");
        require("0x00400000".equals(map.getProperty("textBase")) &&
                "0x10010000".equals(map.getProperty("dataBase")), "Unsupported map segment addresses");
        int textWords = nonnegative(map.getProperty("textWords"));
        int dataBytes = nonnegative(map.getProperty("dataBytes"));
        int count = nonnegative(map.getProperty("stringCount"));
        require(count <= dataBytes, "Invalid map string count");
        List<DataString> strings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String entry = map.getProperty("string." + i, "");
            String[] fields = entry.split(":", -1);
            require(fields.length == 2, "Invalid map string entry");
            strings.add(new DataString(nonnegative(fields[0]), nonnegative(fields[1])));
        }
        Map<String, Symbol> symbols = new LinkedHashMap<>();
        for (String key : map.stringPropertyNames().stream().sorted().toList()) {
            if (!key.startsWith("symbol.")) continue;
            String name = key.substring(7);
            require(NAME.matcher(name).matches(), "Invalid symbol name in map");
            String[] fields = map.getProperty(key).split(":", -1);
            require(fields.length == 2 && (fields[0].equals("text") || fields[0].equals("data")),
                    "Invalid map symbol entry");
            symbols.put(name, new Symbol(fields[0].equals("text"), nonnegative(fields[1])));
        }
        return new Metadata(textWords, dataBytes, symbols, strings);
    }

    private static int nonnegative(String text) {
        try {
            int value = Integer.parseInt(text);
            require(value >= 0, "Negative map field");
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Missing or invalid numeric map field");
        }
    }
    private static void validateMetadata(Metadata metadata, byte[] data) {
        int offset = 0;
        Set<Integer> boundaries = new HashSet<>();
        for (DataString string : metadata.strings) {
            require(string.offset == offset && string.length > 0 &&
                    (long) offset + string.length <= data.length, "Invalid string boundaries in map");
            boundaries.add(offset);
            offset += string.length;
            require(data[offset - 1] == 0, "Data string is missing its terminating NUL");
        }
        require(offset == data.length, "Map does not describe all data bytes");
        boundaries.add(data.length);
        for (Symbol symbol : metadata.symbols.values()) {
            require(symbol.text ? symbol.offset % 4 == 0 && symbol.offset <= metadata.textWords * 4L :
                    boundaries.contains(symbol.offset), "Map label is outside a supported section boundary");
        }
    }

    private static boolean sameFile(Path first, Path second) throws IOException {
        return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize()) ||
                (Files.exists(first) && Files.exists(second) && Files.isSameFile(first, second));
    }
    private static void writeText(Path path, String text) throws IOException {
        writeBytes(path, text.getBytes(StandardCharsets.UTF_8));
    }
    private static void writeBytes(Path path, byte[] bytes) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(path, bytes);
    }
    private static String hex(long value) { return String.format(Locale.ROOT, "0x%08X", value); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
