// a simple cons/nil list

function cons(value, next) {
  return {
    value: value,
    next: next,
    size: function() { return 1 + next.size() },
    forEach: function(consumer) {
      consumer.accept(value)
      next.forEach(consumer)
    }
  }
}

var NIL = {
  size: function() { return 0 },
  forEach: function(consumer) { }
}
function nil() {
  return NIL
}
