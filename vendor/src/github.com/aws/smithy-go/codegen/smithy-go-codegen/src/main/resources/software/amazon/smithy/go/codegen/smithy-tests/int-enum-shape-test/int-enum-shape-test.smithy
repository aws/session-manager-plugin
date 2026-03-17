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

structure Card {
    suit: Suit
    number: Number
}

enum Suit {
    DIAMOND
    CLUB
    HEART
    SPADE
}

intEnum Number {
    ACE   = 1
    TWO   = 2
    THREE = 3
    FOUR  = 4
    FIVE  = 5
    SIX   = 6
    SEVEN = 7
    EIGHT = 8
    NINE  = 9
    TEN   = 10
    JACK  = 11
    QUEEN = 12
    KING  = 13
}
