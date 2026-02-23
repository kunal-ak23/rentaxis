import { useTranslations } from 'next-intl';

export default function HomePage() {
  const t = useTranslations('Index');

  return (
    <div className="min-h-screen flex flex-col items-center justify-center">
      <h1 className="text-4xl font-bold mb-4 text-blue-600">{t('title')}</h1>
      <p className="text-lg text-gray-600 mb-8">
        The Multi-Tenant Rental Management Cloud
      </p>
      <button className="px-6 py-3 bg-blue-600 text-white rounded-lg shadow-md hover:bg-blue-700 transition">
        {t('portal')}
      </button>
    </div>
  );
}
