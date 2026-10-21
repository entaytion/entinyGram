(run) => {
  const TypeErrorCtor = TypeError

  return function transaction(fn) {
    if (typeof fn !== 'function') throw new TypeErrorCtor('Database.transaction: expected a function')
    const database = this
    return function (...args) {
      return run(database, fn, this, args)
    }
  }
}
