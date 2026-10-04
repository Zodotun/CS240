li $t1, 1
li $t2, 101


loop:
li $v0, 1
move $a0, $t1
syscall


li $a0, '\n'
li $v0, 11
syscall
addi $t1, $t1, 1

bne $t1, $t2, loop
