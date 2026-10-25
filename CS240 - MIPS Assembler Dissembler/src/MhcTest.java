import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Dependency-free regression tests, including an independent small execution oracle. */
public final class MhcTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        knownEncodings();
        literalsAndStrings();
        rejectedInputs();
        generatedRoundTrips();
        Path source = args.length == 0 ? Path.of("examples/fizzbuzz2.asm") : Path.of(args[0]);
        fizzBuzz(source);
        System.out.println("PASS: " + assertions + " assertions, including FizzBuzz 1..100.");
    }

    private static void knownEncodings() {
        words("li $t1, 1", 0x24090001);
        words("li $t0, -32768", 0x24088000);
        words("li $t0, 32767", 0x24087FFF);
        words("li $t0, 32768", 0x34088000);
        words("li $t0, 65535", 0x3408FFFF);
        words("li $t0, 65536", 0x3C080001, 0x35080000);
        words("li $t0, 0x89ABCDEF", 0x3C0889AB, 0x3508CDEF);
        words("li $t0, -32769", 0x3C08FFFF, 0x35087FFF);
        words("li $t0, -2147483648", 0x3C088000, 0x35080000);
        words("li $t0, 0xFFFFFFFF", 0x2408FFFF);
        words("li $a0, '\\n'", 0x2404000A);
        words("move $a0, $t1", 0x01202021);
        words("rem $t8, $t1, $t9", 0x0139001A, 0x0000C010);
        words("addi $t1, $t1, 1", 0x21290001);
        words("beqz $t8, next\nnext: syscall", 0x13000000, 12);
        words("again: bne $t1, $t2, again", 0x152AFFFF);
        words("j next\nnext: syscall", 0x08100001, 12);
        words(".data\nfizz: .asciiz \"fizz\"\n.text\nla $a0, fizz", 0x3C041001, 0x34840000);
        // Expanded instructions must count before fixing forward/backward branches.
        words("beqz $t0, end\nli $t1, 65536\nend: syscall", 0x11000002, 0x3C090001, 0x35290000, 12);
        words("lui $t0, 65535\nori $t0, $t0, 32768\naddu $a0, $t1, $zero\n"
                + "div $t1, $t9\nmfhi $t8\naddiu $t0, $zero, -1\nbeq $t0, $t1, 0x00400000",
                0x3C08FFFF, 0x35088000, 0x01202021, 0x0139001A, 0x0000C010, 0x2408FFFF, 0x1109FFF9);
        check(Arrays.equals(Mhc.wordsToBytes(new int[] {0x89ABCDEF}),
                new byte[] {(byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF}), "big-endian bytes");
        check(Mhc.ascii(Mhc.wordsToBytes(new int[] {0x24090001})).equals(
                "00100100000010010000000000000001\n"), "32 readable bits and LF");
        words("bne $0, $31, 0x00420000", 0x141F7FFF);
        words("beq $0, $31, 0x003E0004", 0x101F8000);
        words("j 0x0FFFFFFC", 0x0BFFFFFF);
    }

    private static void literalsAndStrings() {
        check(Mhc.register("$s8") == 30 && Mhc.register("$31") == 31, "register aliases");
        check(Mhc.literal("010") == 10, "leading-zero decimal");
        check(Mhc.literal("0b101") == 5 && Mhc.literal("+0xFF") == 255, "numeric bases");
        check(Mhc.literal("'#'") == 35 && Mhc.literal("','") == 44, "quoted delimiters");
        Mhc.Image image = Mhc.assemble(".data\ns: .asciiz \"a#b,\\n\\t\\\\\\\"\\xFF\\0\" # comment\n"
                + ".text\nli $a0, '#' # comment\nli $a1, ','");
        byte[] expected = {'a', '#', 'b', ',', 10, 9, 92, 34, (byte) 255, 0, 0};
        check(Arrays.equals(image.data(), expected), "string escapes and final NUL");
        check(Mhc.parseAscii(" 00000000\t11111111\r\n")[1] == (byte) 255, "ASCII whitespace");
        check(Mhc.parseAscii("").length == 0 && Mhc.bytesToWords(new byte[0]).length == 0, "empty streams");
        for (int length = 0; length < 25; length++) {
            byte[] bytes = new byte[length];
            for (int i = 0; i < length; i++) bytes[i] = (byte) (i * 31);
            check(Arrays.equals(bytes, Mhc.parseAscii(Mhc.ascii(bytes))), "byte ASCII round trip " + length);
        }
    }

    private static void rejectedInputs() {
        List<String> invalid = List.of(
                "add $t0, $t1, $t2", "lw $t0, 0($sp)", ".word 1", ".globl main",
                "li $32, 1", "li t0, 1", "li $t0", "li $t0, 1, 2", "li $t0, 1,",
                "x: syscall\nx: syscall", "j missing", "beqz $t0, missing",
                "addi $t0, $t1, 32768", "addi $t0, $t1, -32769", "ori $t0, $zero, -1",
                "lui $t0, 65536", "j 0x10000000", "j 0x00400001", "beqz $t0, 0x00400002",
                "beqz $t0, 0x00420004", "beqz $t0, 0x003E0000", "syscall 1",
                ".data\nx: .asciiz \"x\"\n.text\nj x", ".data\nsyscall", ".asciiz \"x\"",
                "li $t0, 4294967296", "li $t0, -2147483649", "li $t0, 999999999999999999999999",
                "li $t0, 0x", "li $t0, --1", "li $t0, 'ab'", "li $t0, ''",
                "li $t0, '\\q'", "li $t0, '\\xG0'", "li $t0, '\\n",
                ".data\n.asciiz \"unterminated", ".data\n.asciiz \"ok\" extra",
                ".data\n.asciiz \"\\q\"", ".data\n.asciiz \"\\x0G\""
        );
        for (String source : invalid) rejects(() -> Mhc.assemble(source), "invalid assembly: " + source);
        rejects(() -> Mhc.parseAscii("0000000X"), "invalid ASCII character");
        rejects(() -> Mhc.parseAscii("101"), "partial byte");
        rejects(() -> Mhc.bytesToWords(new byte[3]), "partial instruction");
        rejects(() -> Mhc.disassemble(new int[] {0}, new byte[0], null), "unsupported opcode");
        rejects(() -> Mhc.disassemble(new int[] {0x00200010}, new byte[0], null), "malformed mfhi");
        rejects(() -> Mhc.disassemble(new int[] {0x3C210000}, new byte[0], null), "malformed lui");
        rejects(() -> Mhc.disassemble(new int[] {0x0000004C}, new byte[0], null), "unsupported syscall code field");
    }

    private static void generatedRoundTrips() {
        java.util.Random random = new java.util.Random(20261025);
        for (int i = 0; i < 500; i++) {
            int value = random.nextInt();
            int rt = 1 + random.nextInt(31);
            Mhc.Image before = Mhc.assemble("li $" + rt + ", " + Integer.toUnsignedString(value));
            String decoded = Mhc.disassemble(before.words(), new byte[0], null);
            check(Arrays.equals(before.words(), Mhc.assemble(decoded).words()), "random li round trip");
            check(run(before)[rt] == value, "random li execution");
        }
        Mhc.Image branches = Mhc.assemble("j 0x00001000\nbeq $t0, $t1, 0x00400000\n"
                + "bne $t0, $t1, 0x00400010\nsyscall");
        check(Arrays.equals(branches.words(), Mhc.assemble(Mhc.disassemble(branches.words(), new byte[0], null)).words()),
                "external and internal targets round trip");
    }

    private static void fizzBuzz(Path source) throws Exception {
        Mhc.Image image = Mhc.assemble(Files.readString(source, StandardCharsets.UTF_8));
        check(image.words().length == 41, "FizzBuzz expanded instruction count");
        check(image.data().length == 19, "FizzBuzz string byte count");
        StringBuilder expected = new StringBuilder();
        for (int i = 1; i <= 100; i++) expected.append(i % 15 == 0 ? "fizzbuzz" :
                i % 3 == 0 ? "fizz" : i % 5 == 0 ? "buzz" : Integer.toString(i)).append('\n');
        StringBuilder actual = new StringBuilder();
        execute(image, actual);
        check(actual.toString().equals(expected.toString()), "independent execution produces FizzBuzz 1..100");
        Path temp = Files.createTempDirectory(Path.of("build"), "mhc-test-");
        Path prefix = temp.resolve("program");
        Mhc.writeImage(image, prefix);
        for (String extension : List.of(".mhc-ascii", ".mhc-bin")) {
            Path input = Path.of(prefix + extension);
            check(Arrays.equals(image.words(), Mhc.readWords(input)), "read " + extension);
            Path decoded = temp.resolve(extension.substring(1) + ".asm");
            Mhc.main(new String[] {"disassemble", input.toString(), decoded.toString()});
            Mhc.Image rebuilt = Mhc.assemble(Files.readString(decoded));
            check(Arrays.equals(image.words(), rebuilt.words()), "instruction round trip " + extension);
            check(Arrays.equals(image.data(), rebuilt.data()), "data round trip " + extension);
            check(image.symbols().equals(rebuilt.symbols()), "label round trip " + extension);
        }
        check(Files.size(Path.of(prefix + ".mhc-bin")) == 164, "four bytes per instruction");
        String bits = Files.readString(Path.of(prefix + ".mhc-ascii"));
        check(bits.lines().allMatch(line -> line.matches("[01]{32}")), "32 bits per ASCII instruction");
        check(bits.replace("\n", "").length() == 41 * 32, "ASCII bit count");
        Files.writeString(Path.of(prefix + ".expected.txt"), expected.toString());
        // Exercise full data reconstruction with escapes, empty strings, and alias labels.
        Mhc.Image special = Mhc.assemble(".data\na: alias: .asciiz \"\\xFF\\0#\\n\\\\\\\"\"\n"
                + "empty: .asciiz \"\"\nend:\n.text\none: two: li $a0, ','\nsyscall\nlast:");
        Path specialPrefix = temp.resolve("special");
        Mhc.writeImage(special, specialPrefix);
        Path specialDecoded = temp.resolve("special.asm");
        Mhc.main(new String[] {"disassemble", specialPrefix + ".mhc-bin", specialDecoded.toString()});
        Mhc.Image specialRebuilt = Mhc.assemble(Files.readString(specialDecoded));
        check(Arrays.equals(special.data(), specialRebuilt.data()), "escaped strings round trip");
        check(special.symbols().equals(specialRebuilt.symbols()), "aliased and end labels round trip");
        try (var paths = Files.walk(temp)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static int[] run(Mhc.Image image) {
        return execute(image, new StringBuilder());
    }

    /** Test-only interpreter, no delay slots (matching the supplied source). */
    private static int[] execute(Mhc.Image image, StringBuilder output) {
        int[] r = new int[32];
        int hi = 0;
        long pc = Mhc.TEXT_BASE;
        for (int steps = 0; steps < 100000; steps++) {
            int index = (int) ((pc - Mhc.TEXT_BASE) / 4);
            if (index == image.words().length) return r;
            if (index < 0 || index >= image.words().length || (pc & 3) != 0)
                throw new AssertionError("Execution left text segment");
            int word = image.words()[index];
            int op = word >>> 26, rs = (word >>> 21) & 31, rt = (word >>> 16) & 31;
            int rd = (word >>> 11) & 31, imm = word & 65535;
            long next = pc + 4;
            switch (op) {
                case 0 -> {
                    switch (word & 63) {
                        case 0x21 -> r[rd] = r[rs] + r[rt];
                        case 0x1A -> {
                            if (r[rt] == 0 || (r[rs] == Integer.MIN_VALUE && r[rt] == -1))
                                throw new AssertionError("Undefined division in test program");
                            hi = r[rs] % r[rt];
                        }
                        case 0x10 -> r[rd] = hi;
                        case 0x0C -> {
                            switch (r[2]) {
                                case 1 -> output.append(r[4]);
                                case 4 -> {
                                    int at = (int) (Integer.toUnsignedLong(r[4]) - Mhc.DATA_BASE);
                                    while (image.data()[at] != 0) output.append((char) (image.data()[at++] & 255));
                                }
                                case 10 -> { return r; }
                                case 11 -> output.append((char) (r[4] & 255));
                                default -> throw new AssertionError("Unsupported test syscall " + r[2]);
                            }
                        }
                        default -> throw new AssertionError("Unknown test instruction");
                    }
                }
                case 2 -> next = ((pc + 4) & 0xF0000000L) | ((word & 0x03FFFFFFL) << 2);
                case 4 -> { if (r[rs] == r[rt]) next += (short) imm * 4L; }
                case 5 -> { if (r[rs] != r[rt]) next += (short) imm * 4L; }
                case 8, 9 -> r[rt] = r[rs] + (short) imm;
                case 13 -> r[rt] = r[rs] | imm;
                case 15 -> r[rt] = imm << 16;
                default -> throw new AssertionError("Unknown test opcode " + op);
            }
            r[0] = 0;
            pc = next;
        }
        throw new AssertionError("Test program exceeded execution step limit");
    }

    private static void words(String source, int... expected) {
        check(Arrays.equals(Mhc.assemble(source).words(), expected), "known encoding: " + source);
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable action, String message) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, message);
    }
}
