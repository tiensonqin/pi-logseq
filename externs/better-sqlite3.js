/** @externs */

/** @constructor */
function Database() {}
Database.prototype.prepare = function(sql) {};
Database.prototype.transaction = function(fn) {};
Database.prototype.exec = function(sql) {};
Database.prototype.close = function() {};
Database.prototype.pragma = function(sql) {};
Database.prototype.function = function(name, fn) {};
Database.prototype.loadExtension = function(path) {};

/** @constructor */
function Statement() {}
Statement.prototype.all = function(...args) {};
Statement.prototype.get = function(...args) {};
Statement.prototype.run = function(...args) {};
Statement.prototype.each = function(...args) {};
Statement.prototype.iterate = function(...args) {};
Statement.prototype.bind = function(...args) {};
Statement.prototype.pluck = function() {};
Statement.prototype.raw = function() {};
