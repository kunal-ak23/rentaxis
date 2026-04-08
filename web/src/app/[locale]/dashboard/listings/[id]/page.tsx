"use client";

import { useState, useEffect, useCallback, use } from "react";
import { useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import {
  ArrowLeft, Save, Eye, EyeOff, Loader2, AlertCircle, CheckCircle2,
  Copy, Check, ChevronUp, ChevronDown, Trash2, Upload, Image as ImageIcon,
  MapPin, Star
} from "lucide-react";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import {
  fetchListing,
  createListing,
  updateListing,
  publishListing,
  unlistListing,
  uploadMedia,
  deleteMedia,
  reorderMedia,
} from "@/lib/api/listings";
import type {
  UnitListingDTO,
  UnitListingMediaDTO,
  ListingAmenity,
  Furnishing,
  ViewType,
  ListingStatus,
} from "@/types/listing";
import { InterestsDrawer } from "../_components/InterestsDrawer";
import { formatCurrencyCompact } from "@/lib/format";

// ──────────── Amenity groups ────────────
const AMENITY_GROUPS: { key: string; amenities: ListingAmenity[] }[] = [
  { key: 'groupRecreation', amenities: ['POOL','GYM','SAUNA','STEAM_ROOM','JACUZZI','KIDS_PLAY_AREA','KIDS_POOL','BBQ_AREA','GARDEN','ROOFTOP_LOUNGE'] },
  { key: 'groupSecurity',   amenities: ['SECURITY_24_7','CCTV','CONCIERGE','INTERCOM','ELEVATOR'] },
  { key: 'groupParking',    amenities: ['COVERED_PARKING','VISITOR_PARKING','EV_CHARGING','NEAR_METRO'] },
  { key: 'groupClimate',    amenities: ['CENTRAL_AC','DISTRICT_COOLING'] },
  { key: 'groupRooms',      amenities: ['MAIDS_ROOM','STUDY_ROOM','STORAGE_ROOM','LAUNDRY_ROOM','BUILT_IN_WARDROBES','BALCONY','PRIVATE_GARDEN'] },
  { key: 'groupFeatures',   amenities: ['MAID_SERVICE','PET_FRIENDLY','SMART_HOME','SOLAR_POWER','SEA_VIEW'] },
  { key: 'groupLocation',   amenities: ['NEAR_SCHOOL','NEAR_MALL','OTHER'] },
];

function amenityLabel(a: ListingAmenity): string {
  return a.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase());
}

function getStatusBadgeClass(status: ListingStatus) {
  switch (status) {
    case 'PUBLISHED': return 'bg-success/10 text-success border border-success/20';
    case 'DRAFT':     return 'bg-input text-muted border border-border';
    case 'UNLISTED':  return 'bg-warning/10 text-warning border border-warning/20';
    case 'UPCOMING':  return 'bg-blue-50 text-blue-600 border border-blue-200';
    default:          return 'bg-input text-muted border border-border';
  }
}

type Tab = 'details' | 'media' | 'amenities' | 'seo' | 'pricing' | 'location';

const TABS: Tab[] = ['details', 'media', 'amenities', 'seo', 'pricing', 'location'];

type UnitOption = { id: string; unitNumber: string; propertyName?: string };

interface FormState {
  unitId: string;
  titleEn: string; titleAr: string;
  descriptionEn: string; descriptionAr: string;
  bedrooms: string; bathrooms: string; sizeSqft: string;
  floor: string; parkingSpaces: string;
  furnishing: string; viewType: string;
  availableFrom: string;
  annualRent: string; securityDeposit: string;
  minLeaseMonths: string; chequesAccepted: string;
  dewaIncluded: boolean; chillerIncluded: boolean;
  utilitiesEstimate: string;
  seoTitle: string; seoDescription: string; seoKeywords: string; ogImageUrl: string;
}

const DEFAULT_FORM: FormState = {
  unitId: '', titleEn: '', titleAr: '',
  descriptionEn: '', descriptionAr: '',
  bedrooms: '', bathrooms: '', sizeSqft: '',
  floor: '', parkingSpaces: '',
  furnishing: '', viewType: '',
  availableFrom: '',
  annualRent: '', securityDeposit: '',
  minLeaseMonths: '', chequesAccepted: '',
  dewaIncluded: false, chillerIncluded: false,
  utilitiesEstimate: '',
  seoTitle: '', seoDescription: '', seoKeywords: '', ogImageUrl: '',
};

export default function ListingEditPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const isNew = id === 'new';
  const t = useTranslations('Listings');
  const router = useRouter();

  const [listing, setListing] = useState<UnitListingDTO | null>(null);
  const [form, setForm] = useState<FormState>(DEFAULT_FORM);
  const [amenities, setAmenities] = useState<Map<ListingAmenity, string>>(new Map());
  const [media, setMedia] = useState<UnitListingMediaDTO[]>([]);
  const [units, setUnits] = useState<UnitOption[]>([]);

  const [activeTab, setActiveTab] = useState<Tab>('details');
  const [loading, setLoading] = useState(!isNew);
  const [saving, setSaving] = useState(false);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [toast, setToast] = useState<{ type: 'success' | 'error'; message: string } | null>(null);
  const [slugCopied, setSlugCopied] = useState(false);

  // Media upload
  const [uploadCaption, setUploadCaption] = useState('');
  const [uploadIsCover, setUploadIsCover] = useState(false);
  const [uploading, setUploading] = useState(false);

  // Interests drawer
  const [showInterests, setShowInterests] = useState(false);

  const showToast = useCallback((type: 'success' | 'error', message: string) => {
    setToast({ type, message });
    setTimeout(() => setToast(null), 3500);
  }, []);

  // Load units list
  useEffect(() => {
    fetch('/api/proxy/v1/units')
      .then(r => r.ok ? r.json() : [])
      .then((data: Array<{ id: string; unitNumber: string; property?: { nameEn?: string } }>) => {
        setUnits(data.map(u => ({
          id: u.id,
          unitNumber: u.unitNumber,
          propertyName: u.property?.nameEn,
        })));
      })
      .catch(() => {});
  }, []);

  // Load existing listing
  useEffect(() => {
    if (isNew) return;
    setLoading(true);
    fetchListing(id)
      .then(data => {
        setListing(data);
        setForm({
          unitId: data.unitId,
          titleEn: data.titleEn,
          titleAr: data.titleAr ?? '',
          descriptionEn: data.descriptionEn ?? '',
          descriptionAr: data.descriptionAr ?? '',
          bedrooms: data.bedrooms != null ? String(data.bedrooms) : '',
          bathrooms: data.bathrooms != null ? String(data.bathrooms) : '',
          sizeSqft: data.sizeSqft != null ? String(data.sizeSqft) : '',
          floor: data.floor != null ? String(data.floor) : '',
          parkingSpaces: data.parkingSpaces != null ? String(data.parkingSpaces) : '',
          furnishing: data.furnishing ?? '',
          viewType: data.viewType ?? '',
          availableFrom: data.availableFrom ?? '',
          annualRent: data.annualRent != null ? String(data.annualRent) : '',
          securityDeposit: data.securityDeposit != null ? String(data.securityDeposit) : '',
          minLeaseMonths: data.minLeaseMonths != null ? String(data.minLeaseMonths) : '',
          chequesAccepted: data.chequesAccepted != null ? String(data.chequesAccepted) : '',
          dewaIncluded: data.dewaIncluded ?? false,
          chillerIncluded: data.chillerIncluded ?? false,
          utilitiesEstimate: data.utilitiesEstimate != null ? String(data.utilitiesEstimate) : '',
          seoTitle: data.seoTitle ?? '',
          seoDescription: data.seoDescription ?? '',
          seoKeywords: data.seoKeywords ?? '',
          ogImageUrl: data.ogImageUrl ?? '',
        });
        const amenityMap = new Map<ListingAmenity, string>();
        for (const a of data.amenities) {
          amenityMap.set(a.amenity, a.customLabel ?? '');
        }
        setAmenities(amenityMap);
        setMedia([...data.media].sort((a, b) => a.sortOrder - b.sortOrder));
      })
      .catch(() => showToast('error', t('saveError')))
      .finally(() => setLoading(false));
  }, [id, isNew, t, showToast]);

  function setField<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm(prev => ({ ...prev, [key]: value }));
  }

  function buildSaveBody() {
    const numOr = (v: string) => v !== '' ? Number(v) : undefined;
    const amenitiesArr = Array.from(amenities.entries()).map(([amenity, customLabel]) => ({
      amenity,
      customLabel: customLabel || undefined,
    }));
    return {
      unitId: form.unitId,
      titleEn: form.titleEn,
      titleAr: form.titleAr || undefined,
      descriptionEn: form.descriptionEn || undefined,
      descriptionAr: form.descriptionAr || undefined,
      bedrooms: numOr(form.bedrooms),
      bathrooms: numOr(form.bathrooms),
      sizeSqft: numOr(form.sizeSqft),
      floor: numOr(form.floor),
      parkingSpaces: numOr(form.parkingSpaces),
      furnishing: (form.furnishing as Furnishing) || undefined,
      viewType: (form.viewType as ViewType) || undefined,
      availableFrom: form.availableFrom || undefined,
      annualRent: numOr(form.annualRent),
      securityDeposit: numOr(form.securityDeposit),
      minLeaseMonths: numOr(form.minLeaseMonths),
      chequesAccepted: numOr(form.chequesAccepted),
      dewaIncluded: form.dewaIncluded,
      chillerIncluded: form.chillerIncluded,
      utilitiesEstimate: numOr(form.utilitiesEstimate),
      seoTitle: form.seoTitle || undefined,
      seoDescription: form.seoDescription || undefined,
      seoKeywords: form.seoKeywords || undefined,
      ogImageUrl: form.ogImageUrl || undefined,
      amenities: amenitiesArr,
    };
  }

  async function handleSave() {
    if (!form.titleEn.trim()) {
      showToast('error', t('required') + ': ' + t('titleEn'));
      setActiveTab('details');
      return;
    }
    setSaving(true);
    try {
      const body = buildSaveBody();
      if (isNew) {
        const created = await createListing(body);
        showToast('success', t('savedSuccess'));
        router.replace(`/dashboard/listings/${created.id}`);
      } else {
        const updated = await updateListing(id, body);
        setListing(updated);
        setMedia([...updated.media].sort((a, b) => a.sortOrder - b.sortOrder));
        showToast('success', t('savedSuccess'));
      }
    } catch {
      showToast('error', t('saveError'));
    } finally {
      setSaving(false);
    }
  }

  async function handlePublish() {
    if (!listing) return;
    setActionLoading('publish');
    try {
      await publishListing(listing.id);
      const updated = await fetchListing(listing.id);
      setListing(updated);
      showToast('success', t('publishSuccess'));
    } catch {
      showToast('error', t('saveError'));
    } finally {
      setActionLoading(null);
    }
  }

  async function handleUnlist() {
    if (!listing) return;
    setActionLoading('unlist');
    try {
      await unlistListing(listing.id);
      const updated = await fetchListing(listing.id);
      setListing(updated);
      showToast('success', t('unlistSuccess'));
    } catch {
      showToast('error', t('saveError'));
    } finally {
      setActionLoading(null);
    }
  }

  async function handleUploadMedia(file: File) {
    if (!listing) return;
    setUploading(true);
    try {
      const fd = new FormData();
      fd.append('file', file);
      if (uploadCaption) fd.append('caption', uploadCaption);
      fd.append('isCover', String(uploadIsCover));
      await uploadMedia(listing.id, fd);
      const updated = await fetchListing(listing.id);
      setMedia([...updated.media].sort((a, b) => a.sortOrder - b.sortOrder));
      setUploadCaption('');
      setUploadIsCover(false);
      showToast('success', t('savedSuccess'));
    } catch {
      showToast('error', t('saveError'));
    } finally {
      setUploading(false);
    }
  }

  async function handleDeleteMedia(mediaId: string) {
    if (!listing) return;
    try {
      await deleteMedia(listing.id, mediaId);
      setMedia(prev => prev.filter(m => m.id !== mediaId));
    } catch {
      showToast('error', t('saveError'));
    }
  }

  async function handleMoveMedia(mediaId: string, direction: 'up' | 'down') {
    if (!listing) return;
    const idx = media.findIndex(m => m.id === mediaId);
    if (idx < 0) return;
    const newMedia = [...media];
    const targetIdx = direction === 'up' ? idx - 1 : idx + 1;
    if (targetIdx < 0 || targetIdx >= newMedia.length) return;
    [newMedia[idx], newMedia[targetIdx]] = [newMedia[targetIdx], newMedia[idx]];
    setMedia(newMedia);
    try {
      await reorderMedia(listing.id, newMedia.map(m => m.id));
    } catch {}
  }

  function copySlug() {
    if (!listing?.slug) return;
    navigator.clipboard.writeText(listing.slug).then(() => {
      setSlugCopied(true);
      setTimeout(() => setSlugCopied(false), 2000);
    });
  }

  function toggleAmenity(amenity: ListingAmenity) {
    setAmenities(prev => {
      const next = new Map(prev);
      if (next.has(amenity)) next.delete(amenity);
      else next.set(amenity, '');
      return next;
    });
  }

  function setAmenityLabel(amenity: ListingAmenity, label: string) {
    setAmenities(prev => {
      const next = new Map(prev);
      next.set(amenity, label);
      return next;
    });
  }

  // ─── Loading skeleton ───
  if (loading) {
    return (
      <div className="animate-pulse">
        <div className="h-6 w-48 bg-input rounded-lg mb-6" />
        <div className="h-10 bg-input/50 rounded-xl mb-4" />
        <div className="h-64 bg-surface rounded-xl border border-border" />
      </div>
    );
  }

  const pageTitle = isNew ? t('newListingTitle') : (listing?.titleEn || t('editTitle'));
  const currentStatus = listing?.status ?? null;

  // ─── Label helpers ───
  const inputClass = "w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none text-foreground placeholder:text-muted/50";
  const labelClass = "block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1";
  const selectClass = "w-full bg-input border border-border p-3 rounded-xl text-xs cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none text-foreground";
  const toggleClass = (on: boolean) => cn(
    "relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2 focus:ring-primary/30",
    on ? "bg-primary" : "bg-input border border-border"
  );
  const toggleDotClass = (on: boolean) => cn(
    "pointer-events-none inline-block h-4 w-4 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out",
    on ? "translate-x-4" : "translate-x-0"
  );

  return (
    <div>
      {/* Toast */}
      {toast && (
        <div className={cn(
          "fixed top-4 right-4 z-[200] flex items-center gap-2.5 px-4 py-3 rounded-xl shadow-lg text-sm font-medium transition-all duration-300",
          toast.type === 'success' ? "bg-success text-white" : "bg-error text-white"
        )}>
          {toast.type === 'success' ? <CheckCircle2 size={16} /> : <AlertCircle size={16} />}
          {toast.message}
        </div>
      )}

      {/* Page header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-8">
        <div className="flex items-center gap-3">
          <Link
            href="/dashboard/listings"
            className="p-2 rounded-lg text-muted hover:text-foreground hover:bg-input transition-colors cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/30"
            aria-label="Back"
          >
            <ArrowLeft size={18} />
          </Link>
          <div>
            <div className="flex items-center gap-2">
              <h1 className="text-xl font-bold text-foreground tracking-tight">{pageTitle}</h1>
              {currentStatus && (
                <span className={cn("inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold border", getStatusBadgeClass(currentStatus))}>
                  {t(`status${currentStatus.charAt(0) + currentStatus.slice(1).toLowerCase()}` as keyof ReturnType<typeof useTranslations>)}
                </span>
              )}
            </div>
            {listing?.slug && (
              <p className="text-xs text-muted mt-0.5 font-mono">{listing.slug}</p>
            )}
          </div>
        </div>

        <div className="flex items-center gap-2 flex-wrap">
          {!isNew && listing && (
            <button
              onClick={() => setShowInterests(true)}
              className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-xs font-semibold bg-primary/10 text-primary hover:bg-primary/20 transition-colors cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/30"
            >
              {t('viewInterests')}
            </button>
          )}
          {/* Publish/Unlist */}
          {!isNew && listing?.status !== 'PUBLISHED' && (
            <button
              onClick={handlePublish}
              disabled={actionLoading === 'publish'}
              className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold bg-success text-white hover:bg-success/90 transition-all cursor-pointer disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-success/30"
            >
              {actionLoading === 'publish' ? <Loader2 size={14} className="animate-spin" /> : <Eye size={14} />}
              {t('actionPublish')}
            </button>
          )}
          {!isNew && listing?.status === 'PUBLISHED' && (
            <button
              onClick={handleUnlist}
              disabled={actionLoading === 'unlist'}
              className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold bg-warning text-white hover:bg-warning/90 transition-all cursor-pointer disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-warning/30"
            >
              {actionLoading === 'unlist' ? <Loader2 size={14} className="animate-spin" /> : <EyeOff size={14} />}
              {t('actionUnlist')}
            </button>
          )}
          <button
            onClick={handleSave}
            disabled={saving}
            className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold bg-primary text-primary-foreground hover:opacity-90 transition-all cursor-pointer disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-primary/30"
          >
            {saving ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />}
            {saving ? t('saving') : (isNew ? t('saveDraft') : t('saveChanges'))}
          </button>
        </div>
      </div>

      {/* Tab bar */}
      <div className="flex items-center gap-0.5 mb-6 border-b border-border overflow-x-auto">
        {TABS.map(tab => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            className={cn(
              "px-4 py-2.5 text-xs font-semibold whitespace-nowrap transition-colors cursor-pointer border-b-2 focus:outline-none",
              activeTab === tab
                ? "border-primary text-primary"
                : "border-transparent text-muted hover:text-foreground"
            )}
          >
            {t(`tab${tab.charAt(0).toUpperCase() + tab.slice(1)}` as keyof ReturnType<typeof useTranslations>)}
          </button>
        ))}
      </div>

      {/* ──────────────────── DETAILS TAB ──────────────────── */}
      {activeTab === 'details' && (
        <div className="bg-surface rounded-xl border border-border p-6">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
            {/* Unit */}
            <div className="col-span-1 md:col-span-2">
              <label className={labelClass}>{t('unitId')} *</label>
              <select
                value={form.unitId}
                onChange={e => setField('unitId', e.target.value)}
                className={selectClass}
                required
                disabled={!isNew}
              >
                <option value="">{t('selectUnit')}</option>
                {units.map(u => (
                  <option key={u.id} value={u.id}>
                    {u.propertyName ? `${u.propertyName} — ` : ''}{u.unitNumber}
                  </option>
                ))}
              </select>
            </div>

            {/* Title EN */}
            <div>
              <label className={labelClass}>{t('titleEn')} *</label>
              <input
                type="text"
                value={form.titleEn}
                onChange={e => setField('titleEn', e.target.value)}
                placeholder="e.g. Stunning 2BR with Sea View"
                className={inputClass}
                required
              />
            </div>

            {/* Title AR */}
            <div>
              <label className={labelClass}>{t('titleAr')}</label>
              <input
                type="text"
                dir="rtl"
                value={form.titleAr}
                onChange={e => setField('titleAr', e.target.value)}
                placeholder="مثال: شقة غرفتين بإطلالة بحرية"
                className={inputClass}
              />
            </div>

            {/* Description EN */}
            <div>
              <label className={labelClass}>{t('descriptionEn')}</label>
              <textarea
                rows={4}
                value={form.descriptionEn}
                onChange={e => setField('descriptionEn', e.target.value)}
                placeholder="Describe the unit..."
                className={cn(inputClass, "resize-y")}
              />
            </div>

            {/* Description AR */}
            <div>
              <label className={labelClass}>{t('descriptionAr')}</label>
              <textarea
                rows={4}
                dir="rtl"
                value={form.descriptionAr}
                onChange={e => setField('descriptionAr', e.target.value)}
                placeholder="وصف الوحدة..."
                className={cn(inputClass, "resize-y")}
              />
            </div>

            {/* Bedrooms */}
            <div>
              <label className={labelClass}>{t('bedrooms')}</label>
              <select value={form.bedrooms} onChange={e => setField('bedrooms', e.target.value)} className={selectClass}>
                <option value="">{t('selectBedrooms')}</option>
                <option value="0">{t('studio0')}</option>
                {[1,2,3,4,5,6,7,8,9,10].map(n => (
                  <option key={n} value={String(n)}>{t(`bed${n}` as keyof ReturnType<typeof useTranslations>)}</option>
                ))}
              </select>
            </div>

            {/* Bathrooms */}
            <div>
              <label className={labelClass}>{t('bathrooms')}</label>
              <select value={form.bathrooms} onChange={e => setField('bathrooms', e.target.value)} className={selectClass}>
                <option value="">{t('selectBathrooms')}</option>
                {[1,2,3,4,5,6,7,8,9,10].map(n => (
                  <option key={n} value={String(n)}>{n}</option>
                ))}
              </select>
            </div>

            {/* Size sqft */}
            <div>
              <label className={labelClass}>{t('sizeSqft')}</label>
              <input
                type="number" min="0"
                value={form.sizeSqft}
                onChange={e => setField('sizeSqft', e.target.value)}
                className={inputClass}
                placeholder="e.g. 1200"
              />
            </div>

            {/* Floor */}
            <div>
              <label className={labelClass}>{t('floor')}</label>
              <input
                type="number" min="0"
                value={form.floor}
                onChange={e => setField('floor', e.target.value)}
                className={inputClass}
                placeholder="e.g. 12"
              />
            </div>

            {/* Parking Spaces */}
            <div>
              <label className={labelClass}>{t('parkingSpaces')}</label>
              <input
                type="number" min="0"
                value={form.parkingSpaces}
                onChange={e => setField('parkingSpaces', e.target.value)}
                className={inputClass}
                placeholder="e.g. 1"
              />
            </div>

            {/* Furnishing */}
            <div>
              <label className={labelClass}>{t('furnishing')}</label>
              <select value={form.furnishing} onChange={e => setField('furnishing', e.target.value)} className={selectClass}>
                <option value="">{t('selectFurnishing')}</option>
                <option value="UNFURNISHED">{t('furnishingUnfurnished')}</option>
                <option value="SEMI_FURNISHED">{t('furnishingSemi')}</option>
                <option value="FULLY_FURNISHED">{t('furnishingFully')}</option>
              </select>
            </div>

            {/* View type */}
            <div>
              <label className={labelClass}>{t('viewType')}</label>
              <select value={form.viewType} onChange={e => setField('viewType', e.target.value)} className={selectClass}>
                <option value="">{t('selectView')}</option>
                <option value="SEA">{t('viewSea')}</option>
                <option value="CITY">{t('viewCity')}</option>
                <option value="POOL">{t('viewPool')}</option>
                <option value="GARDEN">{t('viewGarden')}</option>
                <option value="STREET">{t('viewStreet')}</option>
                <option value="COMMUNITY">{t('viewCommunity')}</option>
                <option value="OTHER">{t('viewOther')}</option>
              </select>
            </div>

            {/* Available from */}
            <div>
              <label className={labelClass}>{t('availableFrom')}</label>
              <input
                type="date"
                value={form.availableFrom}
                onChange={e => setField('availableFrom', e.target.value)}
                className={inputClass}
              />
            </div>
          </div>
        </div>
      )}

      {/* ──────────────────── MEDIA TAB ──────────────────── */}
      {activeTab === 'media' && (
        <div className="bg-surface rounded-xl border border-border p-6 space-y-6">
          {isNew || !listing ? (
            <div className="flex flex-col items-center gap-3 py-12 text-center">
              <div className="w-14 h-14 rounded-2xl bg-input flex items-center justify-center">
                <ImageIcon size={24} className="text-muted" />
              </div>
              <p className="text-sm text-muted font-medium">{t('saveFirstForMedia')}</p>
            </div>
          ) : (
            <>
              {/* Existing media */}
              {media.length > 0 && (
                <div className="space-y-2">
                  {media.map((m, idx) => (
                    <div key={m.id} className="flex items-center gap-3 bg-input/40 rounded-xl border border-border p-3">
                      {/* Thumbnail */}
                      <div className="w-12 h-12 rounded-lg bg-input overflow-hidden shrink-0 border border-border">
                        {m.mediaType === 'PHOTO' || m.mediaType === 'FLOOR_PLAN' ? (
                          // eslint-disable-next-line @next/next/no-img-element
                          <img src={m.url} alt={m.caption ?? ''} className="w-full h-full object-cover" />
                        ) : (
                          <div className="w-full h-full flex items-center justify-center">
                            <ImageIcon size={14} className="text-muted" />
                          </div>
                        )}
                      </div>
                      {/* Info */}
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 mb-0.5">
                          <span className="inline-flex items-center px-1.5 py-0.5 rounded text-[9px] font-bold bg-input text-muted border border-border uppercase">
                            {m.mediaType.replace('_', ' ')}
                          </span>
                          {m.isCover && (
                            <span className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[9px] font-bold bg-primary/10 text-primary border border-primary/20">
                              <Star size={9} /> {t('cover')}
                            </span>
                          )}
                        </div>
                        <p className="text-xs text-foreground truncate">{m.caption ?? m.url}</p>
                      </div>
                      {/* Reorder + Delete */}
                      <div className="flex items-center gap-1 shrink-0">
                        <button
                          onClick={() => handleMoveMedia(m.id, 'up')}
                          disabled={idx === 0}
                          className="p-1.5 rounded-lg text-muted hover:text-foreground hover:bg-input transition-colors cursor-pointer disabled:opacity-30"
                          aria-label={t('moveUp')}
                        >
                          <ChevronUp size={14} />
                        </button>
                        <button
                          onClick={() => handleMoveMedia(m.id, 'down')}
                          disabled={idx === media.length - 1}
                          className="p-1.5 rounded-lg text-muted hover:text-foreground hover:bg-input transition-colors cursor-pointer disabled:opacity-30"
                          aria-label={t('moveDown')}
                        >
                          <ChevronDown size={14} />
                        </button>
                        <button
                          onClick={() => handleDeleteMedia(m.id)}
                          className="p-1.5 rounded-lg text-error hover:bg-error/10 transition-colors cursor-pointer"
                          aria-label={t('deleteMedia')}
                        >
                          <Trash2 size={14} />
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}

              {media.length === 0 && (
                <p className="text-sm text-muted text-center py-6">{t('noMediaYet')}</p>
              )}

              {/* Upload section */}
              <div className="border-t border-border pt-6">
                <h3 className="text-xs font-bold text-foreground uppercase tracking-wider mb-4">{t('uploadMedia')}</h3>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 mb-3">
                  <div>
                    <label className={labelClass}>{t('captionPlaceholder')}</label>
                    <input
                      type="text"
                      value={uploadCaption}
                      onChange={e => setUploadCaption(e.target.value)}
                      placeholder={t('captionPlaceholder')}
                      className={inputClass}
                    />
                  </div>
                  <div className="flex items-center gap-2 mt-5">
                    <button
                      type="button"
                      role="switch"
                      aria-checked={uploadIsCover}
                      onClick={() => setUploadIsCover(v => !v)}
                      className={toggleClass(uploadIsCover)}
                    >
                      <span className={toggleDotClass(uploadIsCover)} />
                    </button>
                    <span className="text-xs text-foreground font-medium">{t('setCoverPhoto')}</span>
                  </div>
                </div>
                <label className={cn(
                  "inline-flex items-center gap-2 px-4 py-2.5 rounded-xl text-xs font-semibold cursor-pointer transition-colors focus-within:ring-2 focus-within:ring-primary/30",
                  uploading ? "bg-input text-muted cursor-not-allowed" : "bg-primary text-primary-foreground hover:opacity-90"
                )}>
                  {uploading ? <Loader2 size={14} className="animate-spin" /> : <Upload size={14} />}
                  {uploading ? t('uploading') : t('upload')}
                  <input
                    type="file"
                    accept="image/jpeg,image/png,image/webp,application/pdf"
                    className="hidden"
                    disabled={uploading}
                    onChange={e => {
                      const file = e.target.files?.[0];
                      if (file) { handleUploadMedia(file); e.target.value = ''; }
                    }}
                  />
                </label>
              </div>
            </>
          )}
        </div>
      )}

      {/* ──────────────────── AMENITIES TAB ──────────────────── */}
      {activeTab === 'amenities' && (
        <div className="bg-surface rounded-xl border border-border p-6 space-y-6">
          {AMENITY_GROUPS.map(group => (
            <div key={group.key}>
              <h3 className="text-[10px] font-bold text-muted uppercase tracking-[0.15em] mb-3">
                {t(group.key as keyof ReturnType<typeof useTranslations>)}
              </h3>
              <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
                {group.amenities.map(amenity => {
                  const checked = amenities.has(amenity);
                  return (
                    <div key={amenity} className="flex flex-col gap-1">
                      <label className="flex items-center gap-2 cursor-pointer group">
                        <input
                          type="checkbox"
                          checked={checked}
                          onChange={() => toggleAmenity(amenity)}
                          className="w-4 h-4 rounded border-border text-primary focus:ring-primary/30 cursor-pointer"
                        />
                        <span className={cn("text-xs font-medium transition-colors", checked ? "text-foreground" : "text-muted group-hover:text-foreground")}>
                          {amenityLabel(amenity)}
                        </span>
                      </label>
                      {checked && amenity === 'OTHER' && (
                        <input
                          type="text"
                          value={amenities.get(amenity) ?? ''}
                          onChange={e => setAmenityLabel(amenity, e.target.value)}
                          placeholder={t('customLabelPlaceholder')}
                          className="ml-6 bg-input border border-border rounded-lg px-2 py-1 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                        />
                      )}
                    </div>
                  );
                })}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* ──────────────────── SEO TAB ──────────────────── */}
      {activeTab === 'seo' && (
        <div className="bg-surface rounded-xl border border-border p-6 space-y-5">
          {/* Slug */}
          {listing?.slug && (
            <div>
              <label className={labelClass}>{t('slug')}</label>
              <div className="flex items-center gap-2">
                <input readOnly value={listing.slug} className={cn(inputClass, "flex-1 text-muted font-mono bg-background cursor-default")} />
                <button
                  onClick={copySlug}
                  className="flex items-center gap-1.5 px-3 py-2.5 rounded-xl text-xs font-semibold bg-input text-foreground hover:bg-input/80 transition-colors cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/30"
                  aria-label={t('copySlug')}
                >
                  {slugCopied ? <Check size={13} className="text-success" /> : <Copy size={13} />}
                  {slugCopied ? t('slugCopied') : t('copySlug')}
                </button>
              </div>
            </div>
          )}

          {/* SEO Title */}
          <div>
            <div className="flex items-center justify-between mb-1.5">
              <label className={cn(labelClass, "mb-0")}>{t('seoTitle')}</label>
              <span className={cn("text-[10px] font-medium", form.seoTitle.length > 60 ? "text-error" : "text-muted")}>
                {60 - form.seoTitle.length} {t('charsRemaining')}
              </span>
            </div>
            <input
              type="text"
              value={form.seoTitle}
              onChange={e => setField('seoTitle', e.target.value)}
              maxLength={80}
              className={inputClass}
            />
          </div>

          {/* SEO Description */}
          <div>
            <div className="flex items-center justify-between mb-1.5">
              <label className={cn(labelClass, "mb-0")}>{t('seoDescription')}</label>
              <span className={cn("text-[10px] font-medium", form.seoDescription.length > 155 ? "text-error" : "text-muted")}>
                {155 - form.seoDescription.length} {t('charsRemaining')}
              </span>
            </div>
            <textarea
              rows={3}
              value={form.seoDescription}
              onChange={e => setField('seoDescription', e.target.value)}
              maxLength={200}
              className={cn(inputClass, "resize-none")}
            />
          </div>

          {/* SEO Keywords */}
          <div>
            <label className={labelClass}>{t('seoKeywords')}</label>
            <input
              type="text"
              value={form.seoKeywords}
              onChange={e => setField('seoKeywords', e.target.value)}
              placeholder="apartment, dubai, sea view"
              className={inputClass}
            />
          </div>

          {/* OG Image URL */}
          <div>
            <label className={labelClass}>{t('ogImageUrl')}</label>
            <input
              type="url"
              value={form.ogImageUrl}
              onChange={e => setField('ogImageUrl', e.target.value)}
              className={inputClass}
            />
          </div>

          {/* Live SERP preview */}
          {(form.seoTitle || form.seoDescription) && (
            <div>
              <h3 className="text-[10px] font-bold text-muted uppercase tracking-[0.15em] mb-3">{t('seoPreviewTitle')}</h3>
              <div className="border border-border rounded-xl p-4 bg-background">
                <p className="text-blue-600 text-sm font-medium mb-0.5 line-clamp-1">
                  {form.seoTitle || form.titleEn || '—'}
                </p>
                <p className="text-[11px] text-green-700 mb-1">
                  rentaxis.com › listings › {listing?.slug ?? 'new-listing'}
                </p>
                <p className="text-xs text-muted line-clamp-2">
                  {form.seoDescription || form.descriptionEn || ''}
                </p>
              </div>
            </div>
          )}
        </div>
      )}

      {/* ──────────────────── PRICING TAB ──────────────────── */}
      {activeTab === 'pricing' && (
        <div className="bg-surface rounded-xl border border-border p-6">
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-5">
            {/* Annual rent */}
            <div>
              <label className={labelClass}>{t('annualRent')}</label>
              <input
                type="number" min="0"
                value={form.annualRent}
                onChange={e => setField('annualRent', e.target.value)}
                placeholder="e.g. 80000"
                className={inputClass}
              />
              {form.annualRent && (
                <p className="text-[11px] text-muted mt-1 ml-1">{formatCurrencyCompact(Number(form.annualRent))}</p>
              )}
            </div>

            {/* Security deposit */}
            <div>
              <label className={labelClass}>{t('securityDeposit')}</label>
              <input
                type="number" min="0"
                value={form.securityDeposit}
                onChange={e => setField('securityDeposit', e.target.value)}
                placeholder="e.g. 6667"
                className={inputClass}
              />
            </div>

            {/* Min lease months */}
            <div>
              <label className={labelClass}>{t('minLeaseMonths')}</label>
              <input
                type="number" min="1" max="36"
                value={form.minLeaseMonths}
                onChange={e => setField('minLeaseMonths', e.target.value)}
                placeholder="e.g. 12"
                className={inputClass}
              />
            </div>

            {/* Cheques accepted */}
            <div>
              <label className={labelClass}>{t('chequesAccepted')}</label>
              <select value={form.chequesAccepted} onChange={e => setField('chequesAccepted', e.target.value)} className={selectClass}>
                <option value="">—</option>
                {[1,2,3,4,6,12].map(n => (
                  <option key={n} value={String(n)}>{n}</option>
                ))}
              </select>
            </div>

            {/* Utilities estimate */}
            <div>
              <label className={labelClass}>{t('utilitiesEstimate')}</label>
              <input
                type="number" min="0"
                value={form.utilitiesEstimate}
                onChange={e => setField('utilitiesEstimate', e.target.value)}
                placeholder="e.g. 500"
                className={inputClass}
              />
            </div>

            {/* DEWA included */}
            <div className="flex items-center gap-3 mt-2">
              <button
                type="button"
                role="switch"
                aria-checked={form.dewaIncluded}
                onClick={() => setField('dewaIncluded', !form.dewaIncluded)}
                className={toggleClass(form.dewaIncluded)}
              >
                <span className={toggleDotClass(form.dewaIncluded)} />
              </button>
              <span className="text-xs font-medium text-foreground">{t('dewaIncluded')}</span>
            </div>

            {/* Chiller included */}
            <div className="flex items-center gap-3 mt-2">
              <button
                type="button"
                role="switch"
                aria-checked={form.chillerIncluded}
                onClick={() => setField('chillerIncluded', !form.chillerIncluded)}
                className={toggleClass(form.chillerIncluded)}
              >
                <span className={toggleDotClass(form.chillerIncluded)} />
              </button>
              <span className="text-xs font-medium text-foreground">{t('chillerIncluded')}</span>
            </div>
          </div>
        </div>
      )}

      {/* ──────────────────── LOCATION TAB ──────────────────── */}
      {activeTab === 'location' && (
        <div className="bg-surface rounded-xl border border-border p-12 flex flex-col items-center gap-4">
          <div className="w-16 h-16 rounded-2xl bg-input flex items-center justify-center">
            <MapPin size={28} className="text-muted/50" />
          </div>
          <p className="text-sm text-muted font-medium text-center">{t('locationComingSoon')}</p>
        </div>
      )}

      {/* Interests drawer */}
      {showInterests && listing && (
        <InterestsDrawer
          listingId={listing.id}
          listingTitle={listing.titleEn}
          onClose={() => setShowInterests(false)}
        />
      )}
    </div>
  );
}
