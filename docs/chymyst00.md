# Concurrency in Reactions: Declarative concurrent programming in Scala

`JoinRun`/`Chymyst` is a library and a framework for declarative concurrent programming.

It follows the **chemical machine** paradigm (known in the academic world as “Join Calculus”) and is implemented as an embedded DSL (domain-specific language) in Scala.

The goal of this tutorial is to explain the chemical machine paradigm and to show examples of implementing concurrent programs in `JoinRun`/`Chymyst`.
To understand this tutorial, the reader should have some familiarity with the `Scala` programming language.

[Chapter 1: The chemical machine](chymyst01.md)

[Chapter 2: Blocking and non-blocking molecules](chymyst02.md)

[Chapter 3: Molecules and injectors, in depth](chymyst03.md)

[Chapter 4: Map/Reduce and Merge-Sort](chymyst04.md)

[Chapter 5: Further concurrency patterns](chymyst05.md)

[Previous work and other tutorials on Join Calculus](chymyst06.md)

The source code repository for `JoinRun` is at [https://github.com/winitzki/joinrun-scala](https://github.com/winitzki/joinrun-scala).

Although this tutorial focuses on using `JoinRun`/`Chymyst` in Scala, one can similarly embed the chemical machine as a library on top of any programming language that has threads and semaphores.
The main concepts and techniques of the chemical machine paradigm are independent of the base programming language.

