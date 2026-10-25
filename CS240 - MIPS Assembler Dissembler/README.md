# MHC: Java assembler and disassembler for fizzbuzz2.asm

A dependency-free Java 17+ command-line project. It assembles the supplied
`fizzbuzz2.asm`, writes actual classic MIPS32 instruction encodings, and
disassembles either requested machine-code format back into assemblable text.
This is a deliberately limited assembler, not a full MIPS toolchain.

## Run the included JAR

Requires Java 17 or newer. From this directory:

```sh
java -jar mhc.jar assemble examples/fizzbuzz2.asm examples/fizzbuzz2
java -jar mhc.jar disassemble examples/fizzbuzz2.mhc-bin examples/decoded.asm
java -jar mhc.jar disassemble examples/fizzbuzz2.mhc-ascii examples/decoded-ascii.asm
java -jar mhc.jar assemble examples/decoded.asm examples/rebuilt
```

The optional second argument to `assemble` is an output prefix, without an
extension. If omitted, the prefix is the input path with a trailing `.asm`
removed. Parent directories are created. Existing generated files at that prefix
are overwritten, so choose a new prefix to keep earlier outputs. The source
file cannot be an output file.

## Exact output formats

| File | Contents for the supplied program |
| --- | --- |
| `fizzbuzz2.mhc-ascii` | 41 lines, each containing exactly 32 readable `0`/`1` characters, followed by LF. 1,312 bits represented as text; 1,353 bytes including the 41 line breaks. |
| `fizzbuzz2.mhc-bin` | 41 instruction words: exactly 164 actual binary bytes, big-endian. No header, separators, BOM, or trailing padding. |
| `fizzbuzz2.data.mhc-ascii` | The string data represented by eight readable bits per byte, grouped up to four bytes per line. The final line may contain fewer than 32 bits because these are data bytes, not instructions. |
| `fizzbuzz2.data.mhc-bin` | Exactly 19 data bytes: `fizz\0buzz\0fizzbuzz\0`. No padding. |
| `fizzbuzz2.mhc-map` | Plaintext section sizes, addresses, symbol names/offsets, and `.asciiz` boundaries. Contains no source instructions or copy of the original source. |

For example, `li $t1, 1` expands to `addiu $t1, $zero, 1`:

```text
mhc-ascii: 00100100000010010000000000000001
mhc-bin:   24 09 00 01    (hex notation showing the four actual bytes)
```

Each 32-bit instruction occupies 32 text characters (plus LF) or four binary
bytes. A pseudoinstruction can expand into two instructions and therefore occupy
64 text characters or eight binary bytes.

The instruction files contain only instructions. Data is separate because text
starts at `0x00400000` while data starts at `0x10010000`; including that address
gap in a raw flat file would waste roughly 252 MiB. Load the instruction bytes at
the text base and the data bytes at the data base. These are raw segment dumps,
not ELF executables or self-loading containers.

## Supported source syntax

Only the nine mnemonics appearing in the attachment are supported, together with
the real instructions necessary to represent their expansions:

| Source instruction | Encoding/expansion |
| --- | --- |
| `li rt, immediate` | Signed 16-bit values: `addiu rt, $zero, immediate`. Other unsigned 16-bit values: `ori rt, $zero, immediate`. All other 32-bit values: `lui rt, upper16` then `ori rt, rt, lower16`. |
| `la rt, label` | Always `lui rt, upper16(address)` then `ori rt, rt, lower16(address)`. Uses the destination register, without reserving `$at`. |
| `move rd, rs` | `addu rd, rs, $zero`. |
| `rem rd, rs, rt` | Signed `div rs, rt` then `mfhi rd`. Changes HI/LO as a consequence of division. |
| `beqz rs, label` | `beq rs, $zero, label`. |
| `addi rt, rs, immediate` | Real ADDI, signed 16-bit immediate. |
| `bne rs, rt, label` | Real BNE, PC-relative signed word offset. |
| `j label` | Real J, aligned target in the `PC + 4` 256 MiB region. |
| `syscall` | Exactly `0x0000000C`, with no explicit code operand. |

The real forms `addiu`, `ori`, `lui`, `addu`, `div`, `mfhi`, and `beq` are also
accepted so that disassembly can be assembled back without changing the bytes.
Unrelated instructions such as `add`, `sub`, `lw`, or `sw` are rejected.

Supported directives are `.data`, `.text`, and `.asciiz`. No `.word`, `.byte`,
`.align`, `.globl`, section-address operands, expressions, relocation, linking,
instruction scheduling, or other assembler macros are implemented.

- Register aliases `$zero` through `$ra`, `$s8` for `$fp`, and `$0` through `$31`.
- Decimal, `0x` hexadecimal, `0b` binary, optional signs, and single-byte character
  literals such as `'\n'`. Leading-zero numbers remain decimal. Constants must
  fit signed or unsigned 32 bits and are interpreted as 32-bit patterns.
- Comma-separated operands; `#` comments outside quoted literals; multiple labels
  on a line; labels with or without an instruction/directive on the same line.
- One double-quoted string per `.asciiz`. Each ends in an extra NUL byte.
  Escapes: `\n`, `\r`, `\t`, `\b`, `\f`, `\0`, `\\`, `\'`, `\"`, and `\xNN`.
  Data characters must fit a byte; this is not a Unicode string encoding.
- Source and decoded `.asm` files are UTF-8. Generated `.mhc-ascii` files are
  ASCII. ASCII input accepts whitespace but rejects any other non-bit character.
- Numeric branch/jump operands are **absolute byte addresses**, not raw encoded
  offsets. Branch offsets are computed as `(target - (PC + 4)) / 4` and checked
  before encoding. Branch/jump label operands must reference text.

## Disassembly and round trips

By default, disassembly looks for `INPUT_PREFIX.mhc-map` beside the instruction
file. When found, it reads `INPUT_PREFIX.data.mhc-bin` for binary input or
`INPUT_PREFIX.data.mhc-ascii` for ASCII input. Keep those companion files
together. Both formats contain the same data, so either complete set works.

```sh
# Explicitly use a differently named map (data still comes from the input prefix):
java -jar mhc.jar disassemble examples/fizzbuzz2.mhc-bin decoded.asm --map examples/fizzbuzz2.mhc-map

# Decode only the instructions, even when a map is present:
java -jar mhc.jar disassemble examples/fizzbuzz2.mhc-bin text-only.asm --text-only
```

With a map, `.data` strings and original label names are restored. Without a
map, disassembly still works: it creates labels for internal branch/jump targets
and uses numeric addresses for external targets. Data and original label names
cannot be recovered from instruction bits alone.

The result uses canonical **real instructions**. Original comments, whitespace,
and the choice of pseudoinstruction spelling cannot be recovered. Reassembling
the result preserves instruction bytes and, with the map/data companions, data
bytes and label addresses. Unknown instructions and malformed reserved fields
are rejected instead of being silently decoded.

## Execution assumptions

The supplied source assumes MARS/SPIM-style services: integer printing (1), string
printing (4), exit (10), and character printing (11), selected through `$v0`.
`syscall` itself has the same encoding for all of these services.

Use **disabled branch delay slots**, as the original program assumes. The tool
does not insert NOPs or rearrange instructions. The output is not suitable for
running this program unchanged on classic hardware with active delay slots.

`rem` uses a compact `div`/`mfhi` expansion. It does not insert MARS's runtime
division checks; division by zero and signed `INT_MIN / -1` are outside the
supported runtime assumptions. The attachment only divides positive values by
3, 5, and 15. Alternative assemblers may choose different valid expansions for
pseudoinstructions, so their byte output need not match this tool's.

## Build and test

A full JDK 17+ is needed to build. No Maven, Gradle, or third-party libraries.

```sh
sh build.sh
# Windows Command Prompt:
build.bat
```

Both scripts compile with Java 17 compatibility and warnings enabled, run the
tests, then build the executable `mhc.jar`. They invoke the JDK's compiler and
JAR modules through `java`; standalone `javac`/`jar` launchers are unnecessary.

Equivalent compiler invocation:

```sh
mkdir -p build/classes
javac --release 17 -Xlint:all -d build/classes src/Mhc.java src/MhcTest.java
java -cp build/classes MhcTest
jar --create --file mhc.jar --main-class Mhc -C build/classes .
```

`src/Mhc.java` contains the parser, two-pass assembler, encoder, binary/ASCII
readers and writers, metadata handling, disassembler, and CLI. It uses bitwise
shifts/masks to build real 32-bit instructions. Pass 1 measures each expanded
instruction before assigning labels; pass 2 resolves and range-checks operands.

`src/MhcTest.java` tests fixed encoding vectors, field boundaries, rejected
syntax/encodings, 500 deterministic random constant loads, both format round
trips, escaped data, labels, and an independent execution check that the generated
FizzBuzz program prints the expected 100 lines. Its tiny interpreter is a
test oracle for this subset, not a general simulator or a production command.

The included `examples/fizzbuzz2.disassembled.asm` and
`examples/fizzbuzz2.expected.txt` show the canonical decoded program and expected
execution output. `VALIDATION.txt` records the checks run for this deliverable.

Encoding references consulted:

- [Cornell MIPS reference sheet](https://www.cs.cornell.edu/courses/cs3410/2018sp/resources/3410_MIPS.pdf)
- [Original MARS pseudoinstruction definitions](https://github.com/dpetersanderson/MARS/blob/main/PseudoOps.txt)
- [MARS syscall documentation](https://dpetersanderson.github.io/Help/SyscallHelp.html)
