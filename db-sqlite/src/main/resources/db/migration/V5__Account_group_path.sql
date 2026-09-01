ALTER TABLE account ADD COLUMN group_path TEXT NOT NULL DEFAULT '[]'; -- JSON array of group names, root first
