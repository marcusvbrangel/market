CREATE USER order_user WITH PASSWORD '1234';
CREATE DATABASE order_db OWNER order_user;

CREATE USER inventory_user WITH PASSWORD '1234';
CREATE DATABASE inventory_db OWNER inventory_user;

CREATE USER notification_user WITH PASSWORD '1234';
CREATE DATABASE notification_db OWNER notification_user;
