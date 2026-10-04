li $t1, 1
li $t2, 101

loop:
addi $t1, $t1, 1
add $t0, $t1, $t0
bne $t1, $t2, loop

li $v0, 1
move $a0, $t0
syscall
