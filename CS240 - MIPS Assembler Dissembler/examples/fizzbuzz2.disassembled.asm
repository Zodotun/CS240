# Canonical MHC disassembly; pseudoinstructions are expanded.
.data
fizz:
    .asciiz "fizz"
buzz:
    .asciiz "buzz"
fizzbuzz:
    .asciiz "fizzbuzz"

.text
    addiu $t1, $zero, 1  # 0x00400000: 24090001
    addiu $t2, $zero, 101  # 0x00400004: 240A0065
    addiu $t3, $zero, 3  # 0x00400008: 240B0003
    addiu $t4, $zero, 5  # 0x0040000C: 240C0005
    addiu $t9, $zero, 15  # 0x00400010: 2419000F
WhichBranch:
    div $t1, $t9  # 0x00400014: 0139001A
    mfhi $t8  # 0x00400018: 0000C010
    beq $t8, $zero, printFizzBuzz  # 0x0040001C: 13000010
    div $t1, $t3  # 0x00400020: 012B001A
    mfhi $t5  # 0x00400024: 00006810
    beq $t5, $zero, printFizz  # 0x00400028: 11A00012
    div $t1, $t4  # 0x0040002C: 012C001A
    mfhi $t5  # 0x00400030: 00006810
    beq $t5, $zero, printBuzz  # 0x00400034: 11A00014
    addiu $v0, $zero, 1  # 0x00400038: 24020001
    addu $a0, $t1, $zero  # 0x0040003C: 01202021
    syscall  # 0x00400040: 0000000C
    j printEnd  # 0x00400044: 08100012
printEnd:
    addiu $v0, $zero, 11  # 0x00400048: 2402000B
    addiu $a0, $zero, 10  # 0x0040004C: 2404000A
    syscall  # 0x00400050: 0000000C
    addi $t1, $t1, 1  # 0x00400054: 21290001
    bne $t1, $t2, WhichBranch  # 0x00400058: 152AFFEE
    j Exit  # 0x0040005C: 08100027
printFizzBuzz:
    addiu $v0, $zero, 4  # 0x00400060: 24020004
    lui $a0, 4097  # 0x00400064: 3C041001
    ori $a0, $a0, 10  # 0x00400068: 3484000A
    syscall  # 0x0040006C: 0000000C
    j printEnd  # 0x00400070: 08100012
printFizz:
    addiu $v0, $zero, 4  # 0x00400074: 24020004
    lui $a0, 4097  # 0x00400078: 3C041001
    ori $a0, $a0, 0  # 0x0040007C: 34840000
    syscall  # 0x00400080: 0000000C
    j printEnd  # 0x00400084: 08100012
printBuzz:
    addiu $v0, $zero, 4  # 0x00400088: 24020004
    lui $a0, 4097  # 0x0040008C: 3C041001
    ori $a0, $a0, 5  # 0x00400090: 34840005
    syscall  # 0x00400094: 0000000C
    j printEnd  # 0x00400098: 08100012
Exit:
    addiu $v0, $zero, 10  # 0x0040009C: 2402000A
    syscall  # 0x004000A0: 0000000C
