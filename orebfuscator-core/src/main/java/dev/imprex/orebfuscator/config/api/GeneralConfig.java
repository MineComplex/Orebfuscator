package dev.imprex.orebfuscator.config.api;

import org.jspecify.annotations.NullMarked;

@NullMarked
public interface GeneralConfig {

  boolean updateOnBlockDamage();

  boolean bypassNotification();

  boolean ignoreSpectator();

  int updateRadius();
}