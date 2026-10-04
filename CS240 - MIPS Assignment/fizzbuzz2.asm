.data
fizz: .asciiz "fizz"
buzz: .asciiz "buzz"
fizzbuzz: .asciiz "fizzbuzz"

.text
li $t1, 1
li $t2, 101
li $t3, 3
li $t4, 5
li $t9, 15

WhichBranch:
rem $t8, $t1, $t9
beqz $t8, printFizzBuzz
rem $t5, $t1, $t3
beqz $t5, printFizz
rem $t5, $t1, $t4
beqz $t5, printBuzz
li $v0, 1
move $a0, $t1
syscall
j printEnd

printEnd:
li $v0, 11
li $a0, '\n'
syscall
addi $t1, $t1, 1
bne $t1, $t2, WhichBranch
j Exit

printFizzBuzz:
li $v0, 4
la $a0, fizzbuzz
syscall
j printEnd

printFizz:
li $v0, 4
la $a0, fizz
syscall
j printEnd

printBuzz:
li $v0, 4
la $a0, buzz
syscall
j printEnd

Exit: 
li $v0, 10
syscall