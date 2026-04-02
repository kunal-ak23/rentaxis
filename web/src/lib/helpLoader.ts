// web/src/lib/helpLoader.ts
import type { HelpArticle } from './help';
import type { UserRole } from './rbac';

/**
 * Parse YAML-like frontmatter from markdown content.
 */
function parseFrontmatter(raw: string): { metadata: Record<string, string | string[]>; content: string } {
  const match = raw.match(/^---\n([\s\S]*?)\n---\n([\s\S]*)$/);
  if (!match) return { metadata: {}, content: raw };

  const metadata: Record<string, string | string[]> = {};
  const lines = match[1].split('\n');
  for (const line of lines) {
    const colonIdx = line.indexOf(':');
    if (colonIdx === -1) continue;
    const key = line.slice(0, colonIdx).trim();
    let value = line.slice(colonIdx + 1).trim();
    if (value.startsWith('[') && value.endsWith(']')) {
      metadata[key] = value.slice(1, -1).split(',').map(v => v.trim());
    } else {
      metadata[key] = value;
    }
  }

  return { metadata, content: match[2].trim() };
}

function toArticle(slug: string, raw: string): HelpArticle {
  const { metadata, content } = parseFrontmatter(raw);
  return {
    slug,
    title: (metadata.title as string) || slug,
    description: (metadata.description as string) || '',
    category: (metadata.category as string) || 'uncategorized',
    roles: (metadata.roles as UserRole[]) || [],
    order: parseInt((metadata.order as string) || '99', 10),
    relatedTour: (metadata.relatedTour as string) || undefined,
    content,
  };
}

// Static article registry - each article's raw markdown content.
// To add a new article: add an entry here with the slug and content.
const ARTICLES_RAW: Record<string, string> = {};

// We need to populate this at build time. Use a dynamic approach with require.context
// or statically list them. Since this is a client component context, we'll use a
// function that reads from a pre-built registry.

// For Next.js, we'll build the registry by importing all .md files.
// The cleanest approach: use a build-time script or manual registry.

let _articles: HelpArticle[] | null = null;

/**
 * Register a raw markdown article. Called during module initialization.
 */
export function registerArticle(slug: string, raw: string) {
  ARTICLES_RAW[slug] = raw;
  _articles = null; // invalidate cache
}

export function getAllArticles(): HelpArticle[] {
  if (_articles) return _articles;
  _articles = Object.entries(ARTICLES_RAW).map(([slug, raw]) => toArticle(slug, raw));
  _articles.sort((a, b) => {
    if (a.category !== b.category) return a.category.localeCompare(b.category);
    return a.order - b.order;
  });
  return _articles;
}

export function getArticleBySlug(slug: string): HelpArticle | undefined {
  return getAllArticles().find(a => a.slug === slug);
}

export function getArticlesByCategory(category: string): HelpArticle[] {
  return getAllArticles().filter(a => a.category === category);
}
