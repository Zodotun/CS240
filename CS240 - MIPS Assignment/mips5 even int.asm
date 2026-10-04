li $t1, 2
li $t2, 102

loop:
li $v0, 1
move $a0, $t1
syscall
add $t0, $t0, $t1
addi $t1, $t1, 2
li $v0, 11
li $a0, '\n'
syscall
bne $t1, $t2, loop
