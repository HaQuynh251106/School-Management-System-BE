-- Remove only the two demo accounts that were introduced for the retired
-- standalone Academic Staff and Accountant workspaces.
DELETE FROM refresh_tokens
WHERE user_id IN ('u-academic-staff-1', 'u-accountant-1');

DELETE FROM password_reset_tokens
WHERE user_id IN ('u-academic-staff-1', 'u-accountant-1');

DELETE FROM email_change_tokens
WHERE user_id IN ('u-academic-staff-1', 'u-accountant-1');

DELETE FROM notification_preferences
WHERE user_id IN ('u-academic-staff-1', 'u-accountant-1');

DELETE FROM user_devices
WHERE user_id IN ('u-academic-staff-1', 'u-accountant-1');

DELETE FROM login_history
WHERE user_id IN ('u-academic-staff-1', 'u-accountant-1');

DELETE FROM users
WHERE id IN ('u-academic-staff-1', 'u-accountant-1');
