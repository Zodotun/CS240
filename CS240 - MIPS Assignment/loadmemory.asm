.data
HelloWorldText: .asciiz "Hello World" 

.text

li $v0, 4
la $a0, HelloWorldText
syscall

