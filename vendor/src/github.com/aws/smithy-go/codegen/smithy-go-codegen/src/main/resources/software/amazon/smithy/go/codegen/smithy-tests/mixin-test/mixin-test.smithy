$version: "2.0"

namespace smithy.example

service Example {
    version: "1.0.0"
    operations: [
        ChangeCard
    ]
}

operation ChangeCard {
    input: Card
    output: Card
}

@mixin
structure CardValuesMixin {
    suit: String
    number: Integer
}

structure Card with [CardValuesMixin] {}
