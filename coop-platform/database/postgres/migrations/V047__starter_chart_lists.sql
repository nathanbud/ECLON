-- Starter setup also loads the chart of accounts' own lists (levels, account types, normal balance kinds), which a new cooperative's chart accounts point to:
-- without them, adding the first chart account fails. Like the other standard lists (V034), the application may only ADD missing rows. MySQL copy: V084.
GRANT INSERT ON legacy.acctlevel TO coop_app;
GRANT INSERT ON legacy.glaccttype TO coop_app;
GRANT INSERT ON legacy.baltype TO coop_app;
