import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import ErrorBoundary from './ErrorBoundary'
import { LanguageProvider } from '../i18n/LanguageContext'

function Boom() {
    throw new Error('测试触发的错误')
}

function Normal() {
    return <div>正常内容</div>
}

describe('ErrorBoundary', () => {
    beforeEach(() => {
        vi.spyOn(console, 'error').mockImplementation(() => {})
        vi.spyOn(window, 'fetch').mockResolvedValue({})
    })

    afterEach(() => {
        vi.restoreAllMocks()
    })

    it('子组件正常时不显示错误 UI', () => {
        render(
            <LanguageProvider>
                <ErrorBoundary>
                    <Normal />
                </ErrorBoundary>
            </LanguageProvider>
        )
        expect(screen.getByText('正常内容')).toBeInTheDocument()
    })

    it('子组件抛错时显示友好提示', () => {
        render(
            <LanguageProvider>
                <ErrorBoundary>
                    <Boom />
                </ErrorBoundary>
            </LanguageProvider>
        )
        expect(screen.getByText('页面加载出了点问题')).toBeInTheDocument()
        expect(screen.getByText('返回首页')).toBeInTheDocument()
        expect(screen.getByText('重新加载')).toBeInTheDocument()
    })

    it('错误详情可展开查看', () => {
        render(
            <LanguageProvider>
                <ErrorBoundary>
                    <Boom />
                </ErrorBoundary>
            </LanguageProvider>
        )
        fireEvent.click(screen.getByText('查看错误详情（可截图反馈）'))
        expect(screen.getByText(/测试触发的错误/)).toBeInTheDocument()
    })

    it('错误会上报到后端 /api/v1/frontend-error', () => {
        render(
            <LanguageProvider>
                <ErrorBoundary>
                    <Boom />
                </ErrorBoundary>
            </LanguageProvider>
        )
        expect(window.fetch).toHaveBeenCalledWith(
            '/api/v1/frontend-error',
            expect.objectContaining({ method: 'POST' })
        )
    })
})
